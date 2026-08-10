const chooseButton = document.getElementById('choose-screen');

const SCREEN_AGENT_CHANNEL = 'video-offer-screen-agent-v1';
const MEDIA_CHUNK_INTERVAL_MS = 2000;
const MAX_PARALLEL_UPLOADS = 4;
const CHUNK_UPLOAD_TIMEOUT_MS = 60000;
const UPLOAD_DRAIN_TIMEOUT_MS = 90000;

const query = new URLSearchParams(location.search);
const agentId = query.get('agentId') || '';

let config = null;
let displayStream = null;
let microphoneStream = null;
let recordingStream = null;
let audioContext = null;
let activeSegment = null;
let completedSegments = new Map();
let previewPeer = null;
let previewPendingCandidates = [];
let intentionalShutdown = false;

chooseButton.disabled = true;
chooseButton.addEventListener('click', chooseScreen);

window.addEventListener('message', event => {
    if (event.origin !== location.origin || event.source !== window.opener) return;
    const message = event.data || {};
    if (message.channel !== SCREEN_AGENT_CHANNEL || message.agentId !== agentId) return;
    void handleMessage(message);
});

window.addEventListener('beforeunload', () => {
    if (!intentionalShutdown) post('AGENT_CLOSED');
});

async function handleMessage(message) {
    try {
        switch (message.type) {
            case 'INIT':
                config = {
                    contextToken: String(message.contextToken || ''),
                    systemAudio: message.systemAudio !== false,
                    microphone: message.microphone !== false
                };
                chooseButton.disabled = !config.contextToken;
                post('INITIALIZED');
                break;
            case 'START_SEGMENT': {
                const segmentIndex = Number(message.segmentIndex);
                try {
                    await startSegment(segmentIndex);
                } catch (error) {
                    post('SEGMENT_ERROR', {segmentIndex, message: error?.message || String(error)});
                }
                break;
            }
            case 'STOP_SEGMENT': {
                const segmentIndex = Number(message.segmentIndex);
                try {
                    await stopSegment(segmentIndex);
                } catch (error) {
                    post('SEGMENT_ERROR', {segmentIndex, message: error?.message || String(error)});
                }
                break;
            }
            case 'PREVIEW_ANSWER':
                await acceptPreviewAnswer(message.description);
                break;
            case 'PREVIEW_ICE':
                await acceptPreviewIce(message.candidate);
                break;
            case 'SHUTDOWN':
                intentionalShutdown = true;
                await shutdownCapture(false);
                try { window.close(); } catch (_) { }
                break;
            default:
                break;
        }
    } catch (error) {
        post('AGENT_ERROR', {message: error?.message || String(error)});
    }
}

async function chooseScreen() {
    if (!config?.contextToken) return;
    if (!navigator.mediaDevices?.getDisplayMedia) {
        post('CAPTURE_ERROR', {name: 'NotSupportedError', message: 'Этот браузер не поддерживает запись экрана.'});
        return;
    }

    chooseButton.disabled = true;
    try {
        await shutdownCapture(false);
        const selected = await navigator.mediaDevices.getDisplayMedia({
            video: true,
            audio: !!config.systemAudio
        });
        const videoTrack = selected.getVideoTracks?.()[0];
        if (!videoTrack) throw new Error('Браузер не передал видеодорожку выбранного экрана.');

        try {
            await videoTrack.applyConstraints({
                width: {max: 1920},
                height: {max: 1080},
                frameRate: {ideal: 30, max: 30}
            });
        } catch (_) { }
        try { videoTrack.contentHint = 'detail'; } catch (_) { }

        let microphone = null;
        if (config.microphone) {
            microphone = await navigator.mediaDevices.getUserMedia({
                video: false,
                audio: {
                    echoCancellation: true,
                    noiseSuppression: true,
                    autoGainControl: true,
                    channelCount: 1
                }
            });
        }

        displayStream = selected;
        microphoneStream = microphone;
        recordingStream = await composeRecordingStream(selected, microphone);
        videoTrack.addEventListener('ended', handleCaptureEnded, {once: true});

        const directPreview = handOffPreviewDirectly(selected);
        if (!directPreview) await startPreviewPeer(selected);

        const settings = videoTrack.getSettings?.() || {};
        post('CAPTURE_READY', {
            details: {
                displaySurface: settings.displaySurface || 'unknown',
                width: settings.width || null,
                height: settings.height || null,
                frameRate: settings.frameRate || null,
                systemAudio: selected.getAudioTracks?.().length > 0,
                microphone: microphone?.getAudioTracks?.().length > 0,
                previewMode: directPreview ? 'direct' : 'webrtc'
            }
        });
        parkWindow();
    } catch (error) {
        await shutdownCapture(false);
        restoreChooserWindow(false);
        const name = error?.name || 'Error';
        let message;
        if (name === 'NotAllowedError' || name === 'PermissionDeniedError') {
            message = 'Доступ к демонстрации экрана не предоставлен.';
        } else if (name === 'AbortError') {
            message = 'Выбор экрана отменён.';
        } else if (name === 'InvalidStateError') {
            message = 'Нажмите «Выбрать экран» ещё раз.';
        } else if (name === 'NotReadableError') {
            message = 'Выбранный экран сейчас нельзя захватить.';
        } else {
            message = error?.message || 'Не удалось получить экран.';
        }
        post('CAPTURE_ERROR', {name, message});
    } finally {
        chooseButton.disabled = !config?.contextToken;
    }
}

function handOffPreviewDirectly(stream) {
    try {
        const receiver = window.opener?.__videoOfferAcceptScreenStream;
        return typeof receiver === 'function' && receiver(agentId, stream) === true;
    } catch (_) {
        return false;
    }
}

async function startPreviewPeer(stream) {
    closePreviewPeer();
    if (typeof RTCPeerConnection === 'undefined') return;
    const videoTrack = stream.getVideoTracks?.()[0];
    if (!videoTrack) return;
    const peer = new RTCPeerConnection({iceServers: []});
    previewPeer = peer;
    previewPendingCandidates = [];
    peer.addTrack(videoTrack, new MediaStream([videoTrack]));
    peer.onicecandidate = event => {
        if (event.candidate) {
            post('PREVIEW_ICE', {candidate: event.candidate.toJSON ? event.candidate.toJSON() : event.candidate});
        }
    };
    const offer = await peer.createOffer();
    await peer.setLocalDescription(offer);
    post('PREVIEW_OFFER', {description: serializeDescription(peer.localDescription)});
}


function serializeDescription(description) {
    return description ? {type: description.type, sdp: description.sdp} : null;
}

async function acceptPreviewAnswer(description) {
    if (!previewPeer || !description) return;
    try {
        await previewPeer.setRemoteDescription(description);
        for (const candidate of previewPendingCandidates.splice(0)) {
            try { await previewPeer.addIceCandidate(candidate); } catch (_) { }
        }
    } catch (_) { }
}

async function acceptPreviewIce(candidate) {
    if (!candidate) return;
    if (!previewPeer || !previewPeer.remoteDescription) {
        previewPendingCandidates.push(candidate);
        return;
    }
    try { await previewPeer.addIceCandidate(candidate); } catch (_) { }
}

function closePreviewPeer() {
    if (previewPeer) {
        try { previewPeer.onicecandidate = null; previewPeer.close(); } catch (_) { }
    }
    previewPeer = null;
    previewPendingCandidates = [];
}

async function composeRecordingStream(screen, microphone) {
    const videoTrack = screen.getVideoTracks?.()[0];
    if (!videoTrack) throw new Error('В выбранном источнике нет видео.');
    const output = new MediaStream([videoTrack]);
    const audioTracks = [
        ...(config.systemAudio ? screen.getAudioTracks?.() || [] : []),
        ...(config.microphone ? microphone?.getAudioTracks?.() || [] : [])
    ];
    if (!audioTracks.length) return output;
    if (audioTracks.length === 1) {
        output.addTrack(audioTracks[0]);
        return output;
    }

    const AudioContextClass = window.AudioContext || window.webkitAudioContext;
    if (!AudioContextClass) {
        output.addTrack(audioTracks[0]);
        return output;
    }
    const context = new AudioContextClass({latencyHint: 'interactive'});
    if (context.state === 'suspended') {
        try { await context.resume(); } catch (_) { }
    }
    const destination = context.createMediaStreamDestination();
    for (const track of audioTracks) {
        const source = context.createMediaStreamSource(new MediaStream([track]));
        source.connect(destination);
    }
    const mixed = destination.stream.getAudioTracks?.()[0];
    if (mixed) output.addTrack(mixed);
    audioContext = context;
    return output;
}

async function startSegment(segmentIndex) {
    if (!Number.isInteger(segmentIndex) || segmentIndex < 0) throw new Error('Некорректный номер сегмента.');
    const screenTrack = displayStream?.getVideoTracks?.()[0];
    if (!recordingStream || !screenTrack || screenTrack.readyState !== 'live') {
        throw new Error('Сначала выберите экран.');
    }
    if (activeSegment) throw new Error('Экран уже записывает сегмент.');

    completedSegments.delete(segmentIndex);
    const mimeType = chooseRecorderMimeType();
    const recorder = mimeType
        ? new MediaRecorder(recordingStream, {mimeType, videoBitsPerSecond: 4000000, audioBitsPerSecond: 128000})
        : new MediaRecorder(recordingStream, {videoBitsPerSecond: 4000000, audioBitsPerSecond: 128000});
    const uploader = new ChunkUploader(
        config.contextToken,
        recorder.mimeType || mimeType || 'video/webm',
        snapshot => post('SEGMENT_PROGRESS', {segmentIndex, percent: snapshot.percent})
    );
    await uploader.init();

    let resolveCompletion;
    let rejectCompletion;
    const completion = new Promise((resolve, reject) => {
        resolveCompletion = resolve;
        rejectCompletion = reject;
    });
    completion.catch(() => {});
    const segment = {
        index: segmentIndex,
        recorder,
        uploader,
        completion,
        resolveCompletion,
        rejectCompletion,
        finalizing: false
    };
    activeSegment = segment;

    recorder.addEventListener('dataavailable', event => {
        if (event.data?.size > 0) uploader.enqueue(event.data);
    });
    recorder.addEventListener('stop', () => { void finalizeSegment(segment); }, {once: true});
    recorder.addEventListener('error', event => {
        const error = event?.error || new Error('Ошибка записи экрана.');
        if (!segment.finalizing) {
            try { if (recorder.state !== 'inactive') recorder.stop(); } catch (_) { }
        }
        post('SEGMENT_ERROR', {segmentIndex, message: error.message});
    });

    const started = new Promise((resolve, reject) => {
        recorder.addEventListener('start', resolve, {once: true});
        recorder.addEventListener('error', event => reject(event?.error || new Error('MediaRecorder не начал запись экрана.')), {once: true});
    });
    recorder.start(MEDIA_CHUNK_INTERVAL_MS);
    await started;
    post('SEGMENT_STARTED', {segmentIndex});
}

async function stopSegment(segmentIndex) {
    const completed = completedSegments.get(segmentIndex);
    if (completed) {
        post('SEGMENT_READY', {segmentIndex, upload: completed});
        return completed;
    }
    const segment = activeSegment;
    if (!segment || segment.index !== segmentIndex) throw new Error('Сегмент экрана не найден.');
    try {
        if (segment.recorder.state !== 'inactive') {
            if (activeSegment === segment) activeSegment = null;
            try { segment.recorder.requestData(); } catch (_) { }
            segment.recorder.stop();
        }
    } catch (error) {
        segment.rejectCompletion(error);
        throw error;
    }
    return segment.completion;
}

async function finalizeSegment(segment) {
    if (segment.finalizing) return segment.completion;
    segment.finalizing = true;
    try {
        const ready = await segment.uploader.finish();
        completedSegments.set(segment.index, ready);
        if (activeSegment === segment) activeSegment = null;
        post('SEGMENT_READY', {segmentIndex: segment.index, upload: ready});
        segment.resolveCompletion(ready);
        return ready;
    } catch (error) {
        if (activeSegment === segment) activeSegment = null;
        post('SEGMENT_ERROR', {segmentIndex: segment.index, message: error?.message || String(error)});
        segment.rejectCompletion(error);
        throw error;
    }
}

function handleCaptureEnded() {
    post('CAPTURE_ENDED');
    if (activeSegment) {
        post('REQUEST_STOP_RECORDING', {reason: 'capture-ended'});
        try {
            if (activeSegment.recorder.state !== 'inactive') activeSegment.recorder.stop();
        } catch (_) { }
    }
    restoreChooserWindow(false);
}

async function shutdownCapture(notify = true) {
    if (activeSegment && activeSegment.recorder.state !== 'inactive') {
        try { activeSegment.recorder.stop(); } catch (_) { }
    }
    closePreviewPeer();
    const tracks = new Set();
    [recordingStream, displayStream, microphoneStream].filter(Boolean).forEach(stream => {
        stream.getTracks?.().forEach(track => tracks.add(track));
    });
    tracks.forEach(track => { try { track.stop(); } catch (_) { } });
    recordingStream = null;
    displayStream = null;
    microphoneStream = null;
    if (audioContext) {
        try { await audioContext.close(); } catch (_) { }
        audioContext = null;
    }
    if (notify) post('CAPTURE_RELEASED');
}

function parkWindow() {
    document.body.classList.add('is-parked');
    document.title = ' ';
    try { window.resizeTo(220, 90); } catch (_) { }
    try {
        const left = Math.max(0, (screen.availLeft || 0) + (screen.availWidth || screen.width || 0) - 230);
        const top = Math.max(0, (screen.availTop || 0) + (screen.availHeight || screen.height || 0) - 120);
        window.moveTo(left, top);
    } catch (_) { }
    setTimeout(() => {
        try { window.blur(); } catch (_) { }
        try { window.opener?.focus(); } catch (_) { }
    }, 50);
}

function restoreChooserWindow(focus = true) {
    document.body.classList.remove('is-parked');
    document.title = 'Выбор экрана';
    try { window.resizeTo(430, 220); } catch (_) { }
    if (focus) {
        try { window.focus(); } catch (_) { }
        setTimeout(() => { try { chooseButton.focus(); } catch (_) { } }, 0);
    }
}

function post(type, payload = {}) {
    if (!window.opener || window.opener.closed || !agentId) return;
    try {
        window.opener.postMessage({channel: SCREEN_AGENT_CHANNEL, agentId, type, ...payload}, location.origin);
    } catch (_) { }
}

function chooseRecorderMimeType() {
    if (!window.MediaRecorder || typeof MediaRecorder.isTypeSupported !== 'function') return '';
    return [
        'video/mp4;codecs=avc1.42E01E,mp4a.40.2',
        'video/mp4',
        'video/webm;codecs=vp8,opus',
        'video/webm'
    ].find(type => MediaRecorder.isTypeSupported(type)) || '';
}

class ChunkUploader {
    constructor(contextTokenValue, mimeType, progressCallback) {
        this.contextToken = contextTokenValue;
        this.mimeType = mimeType || 'application/octet-stream';
        this.progressCallback = progressCallback || (() => {});
        this.session = null;
        this.queue = [];
        this.active = 0;
        this.failure = null;
        this.nextSequence = 0;
        this.completedBytes = 0;
        this.enqueuedBytes = 0;
        this.inFlightBytes = new Map();
        this.waiters = [];
        this.generation = 1;
        this.activeRequests = new Set();
        this.finishing = false;
    }

    async init() {
        const response = await fetch('/bitrix/mobile/uploads', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({
                contextToken: this.contextToken,
                mimeType: this.mimeType,
                sourceKind: 'RECORDING'
            })
        });
        const data = await readJson(response);
        if (!response.ok) throw new Error(data.message || 'Не удалось создать сессию записи.');
        this.session = data;
        return data;
    }

    enqueue(blob) {
        if (!blob || blob.size <= 0 || this.finishing && this.failure) return;
        const sequence = this.nextSequence++;
        this.enqueuedBytes += blob.size;
        this.queue.push({sequence, blob, generation: this.generation});
        this.pump();
    }

    pump() {
        while (!this.failure && this.active < MAX_PARALLEL_UPLOADS && this.queue.length) {
            const task = this.queue.shift();
            this.active++;
            this.uploadChunk(task.blob, task.sequence)
                .then(() => {
                    if (task.generation !== this.generation) return;
                    this.inFlightBytes.delete(task.sequence);
                    this.completedBytes += task.blob.size;
                    this.emitProgress();
                })
                .catch(error => {
                    if (task.generation !== this.generation) return;
                    this.inFlightBytes.delete(task.sequence);
                    if (!this.failure) this.failure = error;
                })
                .finally(() => {
                    if (task.generation !== this.generation) return;
                    this.active = Math.max(0, this.active - 1);
                    this.pump();
                    this.notify();
                });
        }
        this.notify();
    }

    emitProgress(forcedPercent) {
        const activeBytes = [...this.inFlightBytes.values()].reduce((sum, value) => sum + Math.max(0, Number(value) || 0), 0);
        const loadedBytes = this.completedBytes + activeBytes;
        const totalBytes = Math.max(this.enqueuedBytes, loadedBytes, 1);
        const percent = forcedPercent == null
            ? Math.max(0, Math.min(65, Math.round(loadedBytes * 65 / totalBytes)))
            : Math.max(0, Math.min(100, Math.round(forcedPercent)));
        try { this.progressCallback({percent, loadedBytes, totalBytes}); } catch (_) { }
    }

    async uploadChunk(blob, sequence) {
        let lastError;
        for (let attempt = 1; attempt <= 4; attempt++) {
            this.inFlightBytes.set(sequence, 0);
            try {
                return await this.uploadChunkAttempt(blob, sequence);
            } catch (error) {
                this.inFlightBytes.set(sequence, 0);
                lastError = error;
                if (attempt < 4) await sleep(Math.min(1600, attempt * 400));
            }
        }
        throw lastError || new Error('Не удалось загрузить часть записи.');
    }

    uploadChunkAttempt(blob, sequence) {
        return new Promise((resolve, reject) => {
            const xhr = new XMLHttpRequest();
            this.activeRequests.add(xhr);
            xhr.open('PUT', `/bitrix/mobile/uploads/${encodeURIComponent(this.session.id)}/chunks/${sequence}`, true);
            xhr.setRequestHeader('Content-Type', 'application/octet-stream');
            xhr.setRequestHeader('X-Upload-Token', this.session.uploadToken);
            xhr.timeout = CHUNK_UPLOAD_TIMEOUT_MS;
            xhr.upload.onprogress = event => {
                this.inFlightBytes.set(sequence, Math.min(blob.size, Math.max(0, Number(event.loaded) || 0)));
                this.emitProgress();
            };
            xhr.onload = () => {
                this.activeRequests.delete(xhr);
                const data = parseJsonText(xhr.responseText);
                if (xhr.status >= 200 && xhr.status < 300) resolve(data);
                else reject(new Error(data.message || 'Сервер не принял часть записи.'));
            };
            xhr.onerror = () => { this.activeRequests.delete(xhr); reject(new Error('Ошибка сети при загрузке записи.')); };
            xhr.ontimeout = () => { this.activeRequests.delete(xhr); reject(new Error('Сервер слишком долго принимал запись.')); };
            xhr.onabort = () => { this.activeRequests.delete(xhr); reject(new Error('Загрузка записи отменена.')); };
            xhr.send(blob);
        });
    }

    waitDrain(timeoutMs = UPLOAD_DRAIN_TIMEOUT_MS) {
        if (this.failure) return Promise.reject(this.failure);
        if (!this.queue.length && this.active === 0) return Promise.resolve();
        return new Promise((resolve, reject) => {
            const waiter = {resolve, reject, timer: null};
            waiter.timer = setTimeout(() => {
                this.waiters = this.waiters.filter(item => item !== waiter);
                reject(new Error('Не удалось вовремя передать запись на сервер.'));
            }, timeoutMs);
            this.waiters.push(waiter);
        });
    }

    notify() {
        if (this.failure) {
            const items = this.waiters.splice(0);
            items.forEach(item => { clearTimeout(item.timer); item.reject(this.failure); });
            return;
        }
        if (this.queue.length || this.active) return;
        const items = this.waiters.splice(0);
        items.forEach(item => { clearTimeout(item.timer); item.resolve(); });
    }

    async finish() {
        this.finishing = true;
        await this.waitDrain();
        if (this.failure) throw this.failure;
        const chunkCount = this.nextSequence;
        if (chunkCount <= 0) throw new Error('Запись экрана не содержит видеоданных.');
        this.emitProgress(65);
        const response = await fetchWithTimeout(
            `/bitrix/mobile/uploads/${encodeURIComponent(this.session.id)}/complete?chunkCount=${encodeURIComponent(chunkCount)}`,
            {method: 'POST', headers: {'X-Upload-Token': this.session.uploadToken}},
            60000);
        const data = await readJson(response);
        if (!response.ok) throw new Error(data.message || 'Не удалось завершить загрузку записи.');
        this.session = data;
        return this.waitReady();
    }

    async waitReady() {
        const started = Date.now();
        while (Date.now() - started < 20 * 60 * 1000) {
            const response = await fetchWithTimeout(
                `/bitrix/mobile/uploads/${encodeURIComponent(this.session.id)}?uploadToken=${encodeURIComponent(this.session.uploadToken)}`,
                {cache: 'no-store'},
                10000);
            const data = await readJson(response);
            if (!response.ok) throw new Error(data.message || 'Не удалось проверить обработку записи.');
            this.session = data;
            if (data.status === 'READY') {
                this.emitProgress(99);
                return data;
            }
            if (data.status === 'ERROR') throw new Error(data.errorMessage || 'Не удалось обработать запись экрана.');
            const serverProgress = Math.max(0, Math.min(99, Number(data.processingProgressPercent) || 0));
            this.emitProgress(Math.max(66, Math.min(99, 66 + Math.floor(serverProgress * 33 / 100))));
            await sleep(250);
        }
        throw new Error('Обработка записи экрана заняла слишком много времени.');
    }
}

function parseJsonText(text) {
    if (!text) return {};
    try { return JSON.parse(text); } catch (_) { return {message: text}; }
}

async function readJson(response) {
    return parseJsonText(await response.text());
}

async function fetchWithTimeout(url, options, timeoutMs) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    try {
        return await fetch(url, {...options, signal: controller.signal});
    } finally {
        clearTimeout(timer);
    }
}

function sleep(ms) {
    return new Promise(resolve => setTimeout(resolve, ms));
}

if (!agentId || !window.opener) {
    chooseButton.disabled = true;
} else {
    post('AGENT_READY');
    try { chooseButton.focus(); } catch (_) { }
}
