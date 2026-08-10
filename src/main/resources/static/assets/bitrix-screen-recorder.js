const preview = document.getElementById('screen-preview');
const empty = document.getElementById('screen-empty');
const chooseButton = document.getElementById('choose-screen');
const hint = document.getElementById('capture-hint');
const errorBox = document.getElementById('error');
const recBadge = document.getElementById('rec-badge');
const timer = document.getElementById('timer');
const compactTimer = document.getElementById('compact-timer');
const recordControls = document.getElementById('record-controls');
const stopButton = document.getElementById('stop-recording');

const MEDIA_CHUNK_INTERVAL_MS = 2000;
const MAX_PARALLEL_UPLOADS = 4;
const CHUNK_UPLOAD_TIMEOUT_MS = 30000;
const UPLOAD_DRAIN_TIMEOUT_MS = 90000;

let config = null;
let displayStream = null;
let micStream = null;
let recordingStream = null;
let audioContext = null;
let mediaRecorder = null;
let uploadSession = null;
let uploadQueue = [];
let activeUploads = 0;
let uploadFailure = null;
let nextSequence = 0;
let uploadedCount = 0;
let drainWaiters = [];
let generation = 0;
let finalChunkCount = 0;
let startedAt = null;
let timerHandle = null;
let stoppingPromise = null;

function post(type, payload = {}) {
    if (!window.opener || window.opener.closed) return;
    try { window.opener.postMessage({channel: 'video-offer-screen', type, ...payload}, location.origin); } catch (_) { }
}

window.addEventListener('message', async (event) => {
    if (event.origin !== location.origin || event.source !== window.opener) return;
    const message = event.data || {};
    if (message.channel !== 'video-offer-screen') return;
    try {
        if (message.type === 'INIT') {
            config = {
                contextToken: String(message.contextToken || ''),
                systemAudio: message.systemAudio !== false,
                microphone: message.microphone !== false
            };
            post('INITIALIZED');
        } else if (message.type === 'START_SEGMENT') {
            await startSegment(message.segmentIndex);
        } else if (message.type === 'STOP_SEGMENT') {
            await stopSegment(message.segmentIndex);
        } else if (message.type === 'DISCARD_CAPTURE') {
            await shutdownCapture();
        }
    } catch (error) {
        setError(error?.message || 'Ошибка записи экрана');
        post('HELPER_ERROR', {message: error?.message || String(error)});
    }
});

chooseButton.addEventListener('click', chooseScreen);
stopButton.addEventListener('click', () => post('REQUEST_STOP_RECORDING'));

async function chooseScreen() {
    if (!config?.contextToken) {
        setError('Не получен контекст карточки Bitrix24. Закройте это окно и откройте запись экрана заново.');
        return;
    }
    if (!navigator.mediaDevices || typeof navigator.mediaDevices.getDisplayMedia !== 'function') {
        setError('Эта версия браузера не поддерживает запись экрана.');
        return;
    }
    chooseButton.disabled = true;
    setError(null);
    try {
        await shutdownCapture(false);
        const options = {
            video: {displaySurface: 'monitor', frameRate: {ideal: 30, max: 30}},
            audio: !!config.systemAudio,
            selfBrowserSurface: 'exclude',
            surfaceSwitching: 'include',
            monitorTypeSurfaces: 'include',
            systemAudio: config.systemAudio ? 'include' : 'exclude',
            windowAudio: config.systemAudio ? 'system' : 'exclude'
        };
        displayStream = await navigator.mediaDevices.getDisplayMedia(options);
        const videoTrack = displayStream.getVideoTracks()[0];
        if (!videoTrack) throw new Error('Браузер не передал видеопоток экрана.');
        videoTrack.addEventListener('ended', handleCaptureEnded, {once: true});
        micStream = config.microphone ? await acquireMicrophone() : null;
        recordingStream = await composeStream(displayStream, micStream);
        preview.srcObject = displayStream;
        preview.hidden = false;
        empty.hidden = true;
        chooseButton.textContent = 'Выбрать другой экран';
        const settings = videoTrack.getSettings ? videoTrack.getSettings() : {};
        post('CAPTURE_READY', {
            displaySurface: settings.displaySurface || 'unknown',
            hasSystemAudio: displayStream.getAudioTracks().length > 0,
            hasMicrophone: !!micStream?.getAudioTracks().length
        });
    } catch (error) {
        if (error?.name === 'NotAllowedError') {
            setError('Демонстрация экрана не разрешена. Нажмите «Выбрать экран» и подтвердите доступ в системном окне.');
        } else if (error?.name === 'AbortError') {
            setError('Выбор экрана отменён.');
        } else {
            setError(error?.message || 'Не удалось получить экран.');
        }
        post('CAPTURE_ERROR', {name: error?.name || '', message: error?.message || String(error)});
    } finally {
        chooseButton.disabled = false;
    }
}

async function acquireMicrophone() {
    const stream = await navigator.mediaDevices.getUserMedia({
        video: false,
        audio: {echoCancellation: true, noiseSuppression: true, autoGainControl: true, channelCount: 1}
    });
    if (!stream.getAudioTracks().length) {
        stream.getTracks().forEach(track => track.stop());
        throw new Error('Микрофон не передал аудиодорожку.');
    }
    return stream;
}

async function composeStream(screen, microphone) {
    const videoTrack = screen.getVideoTracks()[0];
    const systemTrack = screen.getAudioTracks()[0] || null;
    const micTrack = microphone?.getAudioTracks?.()[0] || null;
    const result = new MediaStream([videoTrack]);
    if (systemTrack && micTrack) {
        const AudioContextClass = window.AudioContext || window.webkitAudioContext;
        if (!AudioContextClass) throw new Error('Браузер не умеет объединять системный звук и микрофон.');
        audioContext = new AudioContextClass();
        try { await audioContext.resume(); } catch (_) { }
        const destination = audioContext.createMediaStreamDestination();
        audioContext.createMediaStreamSource(new MediaStream([systemTrack])).connect(destination);
        audioContext.createMediaStreamSource(new MediaStream([micTrack])).connect(destination);
        const mixed = destination.stream.getAudioTracks()[0];
        if (mixed) result.addTrack(mixed);
    } else if (systemTrack) result.addTrack(systemTrack);
    else if (micTrack) result.addTrack(micTrack);
    return result;
}

async function startSegment(segmentIndex) {
    if (!recordingStream || !displayStream?.getVideoTracks()?.[0] || displayStream.getVideoTracks()[0].readyState !== 'live') {
        throw new Error('Сначала выберите экран.');
    }
    if (mediaRecorder && mediaRecorder.state !== 'inactive') return;
    resetUploader();
    const mimeType = chooseMimeType();
    mediaRecorder = mimeType
        ? new MediaRecorder(recordingStream, {mimeType, videoBitsPerSecond: 1800000, audioBitsPerSecond: 96000})
        : new MediaRecorder(recordingStream, {videoBitsPerSecond: 1800000, audioBitsPerSecond: 96000});
    uploadSession = await createUploadSession(mediaRecorder.mimeType || mimeType || 'video/webm');
    mediaRecorder.addEventListener('dataavailable', onChunk);
    mediaRecorder.addEventListener('error', onRecorderError);
    mediaRecorder.start(MEDIA_CHUNK_INTERVAL_MS);
    startTimer();
    recBadge.hidden = false;
    recordControls.hidden = false;
    chooseButton.disabled = true;
    setCompactMode(true);
    post('SEGMENT_STARTED', {segmentIndex});
}

async function stopSegment(segmentIndex) {
    if (stoppingPromise) return stoppingPromise;
    if (!mediaRecorder || mediaRecorder.state === 'inactive') {
        if (uploadSession?.status === 'READY') post('SEGMENT_READY', {segmentIndex, upload: uploadSession});
        return;
    }
    stoppingPromise = new Promise((resolve, reject) => {
        mediaRecorder.addEventListener('stop', async () => {
            try {
                stopTimer();
                recBadge.hidden = true;
                recordControls.hidden = true;
                setCompactMode(false);
                finalChunkCount = nextSequence;
                await waitForDrain(UPLOAD_DRAIN_TIMEOUT_MS);
                if (uploadFailure) throw uploadFailure;
                if (finalChunkCount <= 0) throw new Error('Запись экрана не содержит видеоданных.');
                uploadSession = await completeUpload(finalChunkCount);
                uploadSession = await waitReady(uploadSession);
                post('SEGMENT_READY', {segmentIndex, upload: uploadSession});
                resolve(uploadSession);
            } catch (error) {
                post('SEGMENT_ERROR', {segmentIndex, message: error?.message || String(error)});
                reject(error);
            } finally {
                mediaRecorder = null;
                stoppingPromise = null;
                chooseButton.disabled = false;
            }
        }, {once: true});
        try { mediaRecorder.stop(); } catch (error) { reject(error); stoppingPromise = null; }
    });
    return stoppingPromise;
}

function onChunk(event) {
    if (!event.data || event.data.size <= 0 || !uploadSession) return;
    const task = {sequence: nextSequence++, blob: event.data, session: uploadSession, generation};
    uploadQueue.push(task);
    pumpUploads();
}

function onRecorderError(event) {
    uploadFailure = event?.error || new Error('MediaRecorder остановился с ошибкой.');
    if (mediaRecorder && mediaRecorder.state !== 'inactive') {
        try { mediaRecorder.stop(); } catch (_) { }
    }
}

function pumpUploads() {
    while (!uploadFailure && activeUploads < MAX_PARALLEL_UPLOADS && uploadQueue.length) {
        const task = uploadQueue.shift();
        activeUploads++;
        uploadChunkWithRetry(task)
            .then(() => { if (task.generation === generation) uploadedCount++; })
            .catch(error => { if (task.generation === generation && !uploadFailure) uploadFailure = error; })
            .finally(() => {
                if (task.generation !== generation) return;
                activeUploads = Math.max(0, activeUploads - 1);
                pumpUploads();
                notifyDrain();
            });
    }
    notifyDrain();
}

async function uploadChunkWithRetry(task) {
    let lastError;
    for (let attempt = 1; attempt <= 4; attempt++) {
        const controller = new AbortController();
        const timeout = setTimeout(() => controller.abort(), CHUNK_UPLOAD_TIMEOUT_MS);
        try {
            const response = await fetch(`/bitrix/mobile/uploads/${encodeURIComponent(task.session.id)}/chunks/${task.sequence}`, {
                method: 'PUT',
                headers: {'Content-Type': 'application/octet-stream', 'X-Upload-Token': task.session.uploadToken},
                body: task.blob,
                signal: controller.signal
            });
            const data = await readJson(response);
            if (!response.ok) throw new Error(data.message || 'Сервер не принял часть записи.');
            return data;
        } catch (error) {
            lastError = error?.name === 'AbortError' ? new Error('Сервер слишком долго принимал часть записи.') : error;
            if (attempt < 4) await sleep(Math.min(1600, 400 * attempt));
        } finally { clearTimeout(timeout); }
    }
    throw lastError || new Error('Не удалось загрузить часть записи.');
}

async function createUploadSession(mimeType) {
    const response = await fetch('/bitrix/mobile/uploads', {
        method: 'POST', headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({contextToken: config.contextToken, mimeType, sourceKind: 'RECORDING'})
    });
    const data = await readJson(response);
    if (!response.ok) throw new Error(data.message || 'Не удалось создать сессию записи.');
    return data;
}

async function completeUpload(chunkCount) {
    const response = await fetchWithTimeout(
        `/bitrix/mobile/uploads/${encodeURIComponent(uploadSession.id)}/complete?chunkCount=${encodeURIComponent(chunkCount)}`,
        {method: 'POST', headers: {'X-Upload-Token': uploadSession.uploadToken}}, 20000);
    const data = await readJson(response);
    if (!response.ok) throw new Error(data.message || 'Не удалось завершить загрузку записи.');
    return data;
}

async function waitReady(session) {
    const started = Date.now();
    let current = session;
    while (Date.now() - started < 20 * 60 * 1000) {
        const response = await fetchWithTimeout(
            `/bitrix/mobile/uploads/${encodeURIComponent(current.id)}?uploadToken=${encodeURIComponent(current.uploadToken)}`,
            {cache: 'no-store'}, 10000);
        const data = await readJson(response);
        if (!response.ok) throw new Error(data.message || 'Не удалось проверить обработку записи.');
        current = data;
        if (data.status === 'READY') return data;
        if (data.status === 'ERROR') throw new Error(data.errorMessage || 'Не удалось обработать запись экрана.');
        await sleep(250);
    }
    throw new Error('Обработка записи экрана заняла слишком много времени.');
}

function resetUploader() {
    generation++;
    uploadQueue = [];
    activeUploads = 0;
    uploadFailure = null;
    nextSequence = 0;
    uploadedCount = 0;
    finalChunkCount = 0;
    drainWaiters.splice(0).forEach(item => item.resolve());
}

function waitForDrain(timeoutMs) {
    if (uploadFailure) return Promise.reject(uploadFailure);
    if (!uploadQueue.length && activeUploads === 0) return Promise.resolve();
    return new Promise((resolve, reject) => {
        const waiter = {resolve, reject, timer: null};
        waiter.timer = setTimeout(() => {
            drainWaiters = drainWaiters.filter(item => item !== waiter);
            reject(new Error('Не удалось вовремя передать запись экрана на сервер.'));
        }, timeoutMs);
        drainWaiters.push(waiter);
    });
}
function notifyDrain() {
    if (uploadFailure) {
        const items = drainWaiters.splice(0);
        items.forEach(item => { clearTimeout(item.timer); item.reject(uploadFailure); });
        return;
    }
    if (uploadQueue.length || activeUploads) return;
    const items = drainWaiters.splice(0);
    items.forEach(item => { clearTimeout(item.timer); item.resolve(); });
}

async function shutdownCapture(notify = true) {
    if (mediaRecorder && mediaRecorder.state !== 'inactive') {
        try { mediaRecorder.stop(); } catch (_) { }
    }
    mediaRecorder = null;
    displayStream?.getTracks()?.forEach(track => { try { track.stop(); } catch (_) { } });
    micStream?.getTracks()?.forEach(track => { try { track.stop(); } catch (_) { } });
    displayStream = null;
    micStream = null;
    recordingStream = null;
    preview.srcObject = null;
    empty.hidden = false;
    recBadge.hidden = true;
    recordControls.hidden = true;
    chooseButton.disabled = false;
    if (audioContext) { try { await audioContext.close(); } catch (_) { } audioContext = null; }
    setCompactMode(false);
    if (notify) post('CAPTURE_RELEASED');
}

function setCompactMode(active) {
    document.body.classList.toggle('is-recording-compact', !!active);
    if (!active) {
        try { window.resizeTo(430, 500); } catch (_) { }
        return;
    }
    try {
        window.resizeTo(340, 190);
        const left = Math.max(0, (screen.availLeft || 0) + (screen.availWidth || screen.width || 0) - 360);
        const top = Math.max(0, (screen.availTop || 0) + 24);
        window.moveTo(left, top);
    } catch (_) { }
}


function handleCaptureEnded() {
    if (mediaRecorder && mediaRecorder.state !== 'inactive') {
        post('REQUEST_STOP_RECORDING', {reason: 'capture-ended'});
    } else {
        post('CAPTURE_ENDED');
    }
    empty.hidden = false;
}

function chooseMimeType() {
    if (!window.MediaRecorder || typeof MediaRecorder.isTypeSupported !== 'function') return '';
    return [
        'video/mp4;codecs=avc1.42E01E,mp4a.40.2', 'video/mp4',
        'video/webm;codecs=vp8,opus', 'video/webm'
    ].find(type => MediaRecorder.isTypeSupported(type)) || '';
}

function startTimer() {
    startedAt = performance.now();
    renderTimer();
    clearInterval(timerHandle);
    timerHandle = setInterval(renderTimer, 250);
}
function stopTimer() { clearInterval(timerHandle); timerHandle = null; renderTimer(); }
function renderTimer() {
    if (startedAt == null) return;
    const seconds = Math.floor((performance.now() - startedAt) / 1000);
    const value = `${String(Math.floor(seconds / 60)).padStart(2,'0')}:${String(seconds % 60).padStart(2,'0')}`;
    timer.textContent = value;
    if (compactTimer) compactTimer.textContent = value;
}
function setError(message) { errorBox.hidden = !message; errorBox.textContent = message || ''; }
async function readJson(response) { const text = await response.text(); if (!text) return {}; try { return JSON.parse(text); } catch (_) { return {message: text}; } }
async function fetchWithTimeout(url, options, timeoutMs) { const c = new AbortController(); const t = setTimeout(()=>c.abort(), timeoutMs); try { return await fetch(url, {...options, signal:c.signal}); } finally { clearTimeout(t); } }
function sleep(ms) { return new Promise(resolve => setTimeout(resolve, ms)); }

window.addEventListener('beforeunload', () => post('HELPER_CLOSED'));
post('HELPER_READY');
