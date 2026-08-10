const form = document.getElementById('bitrix-offer-form');
const contextToken = document.getElementById('context-token').value;
const sourceModeInput = document.getElementById('source-mode');
const sourcePicker = document.getElementById('source-picker');
const linkSourceSection = document.getElementById('link-source-section');
const recordSourceSection = document.getElementById('record-source-section');
const offerFields = document.getElementById('offer-fields');
const recordingUrlInput = document.getElementById('recording-url');
const submitButton = document.getElementById('submit-button');
const formError = document.getElementById('form-error');
const processing = document.getElementById('processing');
const processingStatus = document.getElementById('processing-status');
const processingPercent = document.getElementById('processing-percent');
const progressBar = document.getElementById('progress-bar');
const deliveryStatus = document.getElementById('delivery-status');
const readyResult = document.getElementById('ready-result');
const readyMessage = document.getElementById('ready-message');
const publicLink = document.getElementById('public-link');

const cameraPreview = document.getElementById('camera-preview');
const cameraPlaceholder = document.getElementById('camera-placeholder');
const cameraError = document.getElementById('camera-error');
const startCameraButton = document.getElementById('start-camera');
const switchCameraButton = document.getElementById('switch-camera');
const recordToggleButton = document.getElementById('record-toggle');
const playRecordingButton = document.getElementById('play-recording');
const playGlyph = document.getElementById('play-glyph');
const recordingBadge = document.getElementById('recording-badge');
const recordingTimer = document.getElementById('recording-timer');
const uploadProcessing = document.getElementById('upload-processing');
const uploadStatus = document.getElementById('upload-status');
const uploadProgressText = document.getElementById('upload-progress-text');
const uploadProgressBar = document.getElementById('upload-save-progress-bar');

const MEDIA_CHUNK_INTERVAL_MS = 2000;
const MAX_PARALLEL_UPLOADS = 4;
const CHUNK_UPLOAD_TIMEOUT_MS = 30_000;
const UPLOAD_DRAIN_TIMEOUT_MS = 90_000;

let activeOfferId = null;
let pollTimer = null;

let cameraStream = null;
let cameraDevices = [];
let activeCameraIndex = 0;
let mediaRecorder = null;
let recordingStartedAt = null;
let timerHandle = null;
let recordedPreviewUrl = null;
let recordingFinalizing = false;

let uploadSession = null;
let uploadFailure = null;
let uploadQueue = [];
let activeChunkUploads = 0;
let nextChunkSequence = 0;
let uploadedChunkCount = 0;
let uploadedChunkBytes = 0;
let uploadDrainWaiters = [];
let activeUploadControllers = new Set();
let finalChunkCount = 0;
let uploadGeneration = 0;

initializeBitrixFrame();
initializeSourcePicker();
initializeRecorder();

form.addEventListener('submit', async (event) => {
    event.preventDefault();
    clearInterval(pollTimer);
    setError(null);
    readyResult.hidden = true;
    processing.hidden = false;
    submitButton.disabled = true;
    submitButton.textContent = 'Создаём…';
    renderProgress(sourceModeInput.value === 'RECORD' ? 100 : 0, 'Создаём видеооффер…');
    fitWindow();

    try {
        let response;
        if (sourceModeInput.value === 'RECORD') {
            if (!uploadSession || uploadSession.status !== 'READY') {
                throw new Error('Сначала запишите и дождитесь подготовки видео.');
            }
            response = await fetch(`/bitrix/mobile/uploads/${encodeURIComponent(uploadSession.id)}/offer`, {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({
                    uploadToken: uploadSession.uploadToken,
                    accompanyingText: document.getElementById('accompanying-text').value.trim() || null,
                    viewNotificationGoal: document.getElementById('view-notification-goal').value
                })
            });
        } else {
            const payload = {
                contextToken,
                recordingUrl: recordingUrlInput.value.trim(),
                accompanyingText: document.getElementById('accompanying-text').value.trim() || null,
                viewNotificationGoal: document.getElementById('view-notification-goal').value
            };
            response = await fetch('/bitrix/video-offers', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify(payload)
            });
        }

        const data = await readJson(response);
        if (!response.ok) {
            throw new Error(data.message || 'Не удалось создать видеооффер');
        }

        activeOfferId = data.id;
        renderOffer(data);
        const deliveryFinished = data.bitrixDeliveryStatus !== 'PENDING'
            && data.bitrixDeliveryStatus !== 'SENDING';
        if (data.status === 'READY' && deliveryFinished) {
            submitButton.disabled = false;
            submitButton.textContent = 'Сформировать ещё один';
        } else {
            pollTimer = setInterval(checkStatus, 1000);
            await checkStatus();
        }
    } catch (error) {
        finishWithError(error.message);
    }
});

function initializeSourcePicker() {
    sourcePicker.querySelectorAll('[data-source]').forEach((button) => {
        button.addEventListener('click', async () => {
            const nextMode = button.dataset.source;
            if (!nextMode || nextMode === sourceModeInput.value) return;
            await switchSourceMode(nextMode);
        });
    });
}

async function switchSourceMode(mode) {
    sourceModeInput.value = mode;
    sourcePicker.querySelectorAll('[data-source]').forEach((button) => {
        const active = button.dataset.source === mode;
        button.classList.toggle('is-active', active);
        button.setAttribute('aria-pressed', active ? 'true' : 'false');
    });

    clearInterval(pollTimer);
    activeOfferId = null;
    processing.hidden = true;
    readyResult.hidden = true;
    setError(null);

    const recordMode = mode === 'RECORD';
    linkSourceSection.hidden = recordMode;
    recordSourceSection.hidden = !recordMode;
    recordingUrlInput.required = !recordMode;
    offerFields.hidden = recordMode;
    submitButton.hidden = recordMode;
    submitButton.disabled = false;
    submitButton.textContent = 'Сформировать видеооффер';

    if (recordMode) {
        await resetRecorder(false);
        recordSourceSection.hidden = false;
        offerFields.hidden = true;
        submitButton.hidden = true;
        await startCamera(false);
    } else {
        await resetRecorder(false);
        offerFields.hidden = false;
        submitButton.hidden = false;
    }
    fitWindow();
}

function initializeRecorder() {
    startCameraButton.addEventListener('click', () => startCamera(false));
    switchCameraButton.addEventListener('click', switchCamera);
    recordToggleButton.addEventListener('click', toggleRecording);
    playRecordingButton.addEventListener('click', toggleRecordedPlayback);
    cameraPreview.addEventListener('play', updatePlayButtonState);
    cameraPreview.addEventListener('pause', updatePlayButtonState);
    cameraPreview.addEventListener('ended', updatePlayButtonState);
}

async function startCamera(switching) {
    if (sourceModeInput.value !== 'RECORD') return false;
    if (!supportsEmbeddedRecording()) {
        setCameraError('Этот браузер не поддерживает запись через MediaRecorder.');
        startCameraButton.hidden = true;
        return false;
    }

    clearCameraError();
    startCameraButton.disabled = true;
    startCameraButton.textContent = switching ? 'Переключаем…' : 'Включаем…';

    try {
        stopCameraStream();
        clearRecordedPreview();

        let videoConstraint = {
            width: {ideal: 1280, max: 1280},
            height: {ideal: 720, max: 1280},
            frameRate: {ideal: 30, max: 30}
        };
        if (cameraDevices.length > 0 && cameraDevices[activeCameraIndex]) {
            videoConstraint = {
                ...videoConstraint,
                deviceId: {exact: cameraDevices[activeCameraIndex].deviceId}
            };
        }

        cameraStream = await navigator.mediaDevices.getUserMedia({
            video: videoConstraint,
            audio: {
                echoCancellation: true,
                noiseSuppression: true,
                channelCount: 1
            }
        });

        cameraPreview.removeAttribute('src');
        cameraPreview.srcObject = cameraStream;
        cameraPreview.autoplay = true;
        cameraPreview.muted = true;
        cameraPreview.hidden = false;
        cameraPlaceholder.hidden = true;
        try { await cameraPreview.play(); } catch (_) {}

        await refreshCameraDevices();
        const currentDeviceId = cameraStream.getVideoTracks()[0]?.getSettings()?.deviceId;
        const currentIndex = cameraDevices.findIndex((device) => device.deviceId === currentDeviceId);
        if (currentIndex >= 0) activeCameraIndex = currentIndex;

        switchCameraButton.hidden = cameraDevices.length < 2;
        switchCameraButton.disabled = false;
        recordToggleButton.hidden = false;
        recordToggleButton.disabled = false;
        startCameraButton.hidden = true;
        playRecordingButton.hidden = true;
        updateRecordButtonState(false);
        fitWindow();
        return true;
    } catch (error) {
        stopCameraStream();
        cameraPlaceholder.hidden = false;
        switchCameraButton.hidden = true;
        recordToggleButton.hidden = true;
        startCameraButton.hidden = false;
        setCameraError(cameraErrorMessage(error));
        fitWindow();
        return false;
    } finally {
        startCameraButton.disabled = false;
        startCameraButton.textContent = 'Включить камеру';
    }
}

async function refreshCameraDevices() {
    if (!navigator.mediaDevices || typeof navigator.mediaDevices.enumerateDevices !== 'function') {
        cameraDevices = [];
        return;
    }
    try {
        cameraDevices = (await navigator.mediaDevices.enumerateDevices())
            .filter((device) => device.kind === 'videoinput' && device.deviceId);
    } catch (_) {
        cameraDevices = [];
    }
}

async function switchCamera() {
    if (isRecordingActive() || recordingFinalizing) return;
    await refreshCameraDevices();
    if (cameraDevices.length < 2) return;
    activeCameraIndex = (activeCameraIndex + 1) % cameraDevices.length;
    switchCameraButton.disabled = true;
    try {
        await startCamera(true);
    } finally {
        switchCameraButton.disabled = false;
    }
}

async function toggleRecording() {
    if (recordingFinalizing) return;
    if (isRecordingActive()) {
        stopRecording();
        return;
    }

    if (uploadSession) {
        if (uploadSession.status === 'CONSUMED') {
            uploadSession = null;
            resetChunkUploader();
            clearRecordedPreview();
            offerFields.hidden = true;
            submitButton.hidden = true;
        } else {
            await discardCurrentUpload();
        }
    }

    if (!cameraStream) {
        const started = await startCamera(false);
        if (!started) return;
    }
    await startRecording();
}

async function startRecording() {
    if (!cameraStream || isRecordingActive()) return;
    clearCameraError();
    resetChunkUploader();
    uploadProcessing.hidden = true;
    offerFields.hidden = true;
    submitButton.hidden = true;
    processing.hidden = true;
    readyResult.hidden = true;
    recordingFinalizing = false;

    try {
        const mimeType = chooseRecorderMimeType();
        const options = {
            videoBitsPerSecond: 1_800_000,
            audioBitsPerSecond: 96_000
        };
        if (mimeType) options.mimeType = mimeType;

        try {
            mediaRecorder = new MediaRecorder(cameraStream, options);
        } catch (_) {
            mediaRecorder = new MediaRecorder(cameraStream);
        }

        uploadSession = await createUploadSession(mediaRecorder.mimeType || mimeType || 'video/webm');
        mediaRecorder.addEventListener('dataavailable', handleRecordedChunk);
        mediaRecorder.addEventListener('error', handleRecorderError);
        mediaRecorder.addEventListener('stop', finalizeRecordedVideo, {once: true});

        await new Promise((resolve, reject) => {
            mediaRecorder.addEventListener('start', resolve, {once: true});
            mediaRecorder.addEventListener('error', (event) => {
                reject(event?.error || new Error('MediaRecorder не начал запись'));
            }, {once: true});
            mediaRecorder.start(MEDIA_CHUNK_INTERVAL_MS);
        });

        startTimer();
        switchCameraButton.disabled = true;
        recordingBadge.hidden = false;
        playRecordingButton.hidden = true;
        updateRecordButtonState(true);
        fitWindow();
    } catch (error) {
        stopTimer(true);
        mediaRecorder = null;
        updateRecordButtonState(false);
        setCameraError(error.message || 'Не удалось начать запись');
        if (uploadSession) await discardCurrentUpload();
    }
}

function handleRecorderError(event) {
    uploadFailure = event?.error || new Error('Ошибка MediaRecorder');
    if (mediaRecorder && mediaRecorder.state !== 'inactive') {
        try { mediaRecorder.stop(); } catch (_) {}
    }
}

function handleRecordedChunk(event) {
    if (!event.data || event.data.size <= 0 || !uploadSession) return;
    enqueueChunkUpload(event.data);
}

function enqueueChunkUpload(blob) {
    const sequence = nextChunkSequence++;
    uploadQueue.push({sequence, blob, generation: uploadGeneration, session: uploadSession});
    pumpChunkUploads();
}

function pumpChunkUploads() {
    while (!uploadFailure && activeChunkUploads < MAX_PARALLEL_UPLOADS && uploadQueue.length > 0) {
        const task = uploadQueue.shift();
        activeChunkUploads++;
        uploadChunkWithRetry(task.blob, task.sequence, task.session)
            .then((data) => {
                if (task.generation !== uploadGeneration) return;
                uploadedChunkCount++;
                uploadedChunkBytes += task.blob.size;
                if (uploadSession && task.session && uploadSession.id === task.session.id && data) {
                    uploadSession = {
                        ...uploadSession,
                        status: data.status || uploadSession.status,
                        bytesReceived: Math.max(
                            Number(uploadSession.bytesReceived || 0),
                            Number(data.bytesReceived || 0),
                            uploadedChunkBytes)
                    };
                }
                renderUploadSaveProgress();
            })
            .catch((error) => {
                if (task.generation !== uploadGeneration) return;
                if (!uploadFailure) {
                    uploadFailure = error;
                    setCameraError('Не удалось сохранить запись: ' + (error.message || 'ошибка сети'));
                    if (mediaRecorder && mediaRecorder.state !== 'inactive') {
                        try { mediaRecorder.stop(); } catch (_) {}
                    }
                }
            })
            .finally(() => {
                if (task.generation !== uploadGeneration) return;
                activeChunkUploads = Math.max(0, activeChunkUploads - 1);
                pumpChunkUploads();
                notifyUploadDrainWaiters();
            });
    }
    notifyUploadDrainWaiters();
}

function stopRecording() {
    if (!isRecordingActive() || recordingFinalizing) return;
    recordingFinalizing = true;
    recordToggleButton.disabled = true;
    stopTimer();
    updateRecordButtonState(false);
    uploadProcessing.hidden = false;
    uploadStatus.textContent = 'Сохраняем видео…';
    setUploadProgress(0);
    try {
        mediaRecorder.stop();
    } catch (error) {
        recordingFinalizing = false;
        recordToggleButton.disabled = false;
        updateRecordButtonState(true);
        setCameraError(error.message || 'Не удалось остановить запись');
    }
}

async function finalizeRecordedVideo() {
    stopTimer();
    recordingBadge.hidden = true;
    switchCameraButton.disabled = false;
    recordToggleButton.disabled = true;
    stopCameraStream();
    finalChunkCount = nextChunkSequence;
    renderUploadSaveProgress();

    try {
        await waitForChunkUploads(UPLOAD_DRAIN_TIMEOUT_MS);
        if (uploadFailure) throw uploadFailure;
        if (finalChunkCount <= 0) throw new Error('Запись не содержит видеоданных');
        setUploadProgress(100);
        uploadStatus.textContent = 'Подготавливаем видео…';
        uploadSession = await completeUploadSession(finalChunkCount);
        await waitForNormalization();
    } catch (error) {
        setCameraError(error.message || 'Не удалось сохранить видео');
        uploadProcessing.hidden = true;
        startCameraButton.hidden = false;
        recordToggleButton.hidden = false;
        playRecordingButton.hidden = true;
        updateRecordButtonState(false);
    } finally {
        mediaRecorder = null;
        recordingFinalizing = false;
        recordToggleButton.disabled = false;
        fitWindow();
    }
}

async function createUploadSession(mimeType) {
    const response = await fetch('/bitrix/mobile/uploads', {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({
            contextToken,
            mimeType: mimeType || 'application/octet-stream'
        })
    });
    const data = await readJson(response);
    if (!response.ok) throw new Error(data.message || 'Не удалось создать сессию записи');
    return data;
}

async function uploadChunkWithRetry(blob, sequence, session) {
    let lastError = null;
    for (let attempt = 1; attempt <= 4; attempt++) {
        const controller = new AbortController();
        activeUploadControllers.add(controller);
        const timeout = setTimeout(() => controller.abort(), CHUNK_UPLOAD_TIMEOUT_MS);
        try {
            const response = await fetch(
                `/bitrix/mobile/uploads/${encodeURIComponent(session.id)}/chunks/${sequence}`,
                {
                    method: 'PUT',
                    headers: {
                        'Content-Type': 'application/octet-stream',
                        'X-Upload-Token': session.uploadToken
                    },
                    body: blob,
                    signal: controller.signal
                });
            const data = await readJson(response);
            if (!response.ok) throw new Error(data.message || 'Сервер не принял часть видео');
            return data;
        } catch (error) {
            lastError = error?.name === 'AbortError'
                ? new Error('Сервер слишком долго принимал видео')
                : error;
            if (attempt < 4) await sleep(Math.min(1600, attempt * 400));
        } finally {
            clearTimeout(timeout);
            activeUploadControllers.delete(controller);
        }
    }
    throw lastError || new Error('Не удалось загрузить часть видео');
}

async function completeUploadSession(chunkCount) {
    const response = await fetchWithTimeout(
        `/bitrix/mobile/uploads/${encodeURIComponent(uploadSession.id)}/complete?chunkCount=${encodeURIComponent(chunkCount)}`,
        {
            method: 'POST',
            headers: {'X-Upload-Token': uploadSession.uploadToken}
        },
        20_000);
    const data = await readJson(response);
    if (!response.ok) throw new Error(data.message || 'Не удалось завершить загрузку видео');
    return data;
}

async function waitForNormalization() {
    const startedAt = Date.now();
    while (Date.now() - startedAt < 20 * 60 * 1000) {
        const response = await fetchWithTimeout(
            `/bitrix/mobile/uploads/${encodeURIComponent(uploadSession.id)}?uploadToken=${encodeURIComponent(uploadSession.uploadToken)}`,
            {cache: 'no-store'},
            10_000);
        const data = await readJson(response);
        if (!response.ok) throw new Error(data.message || 'Не удалось проверить обработку видео');
        uploadSession = data;
        if (data.status === 'READY') {
            showNormalizedVideo(data);
            return;
        }
        if (data.status === 'ERROR') {
            throw new Error(data.errorMessage || 'Не удалось обработать видео');
        }
        uploadStatus.textContent = 'Подготавливаем видео…';
        await sleep(Date.now() - startedAt < 8000 ? 250 : 750);
    }
    throw new Error('Обработка видео заняла слишком много времени');
}

function showNormalizedVideo(data) {
    uploadProcessing.hidden = true;
    stopCameraStream();
    recordedPreviewUrl = data.previewUrl || '';
    cameraPlaceholder.hidden = true;
    cameraPreview.hidden = false;
    cameraPreview.autoplay = false;
    cameraPreview.muted = false;
    cameraPreview.srcObject = null;
    cameraPreview.src = recordedPreviewUrl;
    cameraPreview.load();
    switchCameraButton.hidden = true;
    startCameraButton.hidden = true;
    recordToggleButton.hidden = false;
    recordToggleButton.disabled = false;
    updateRecordButtonState(false);
    playRecordingButton.hidden = !recordedPreviewUrl;
    updatePlayButtonState();
    offerFields.hidden = false;
    submitButton.hidden = false;
    submitButton.disabled = false;
    submitButton.textContent = 'Сформировать видеооффер';
    clearCameraError();
    fitWindow();
}

async function resetRecorder(showCameraButton) {
    stopTimer(true);
    const previousUpload = uploadSession;
    if (mediaRecorder) {
        try {
            mediaRecorder.removeEventListener('dataavailable', handleRecordedChunk);
            mediaRecorder.removeEventListener('error', handleRecorderError);
            mediaRecorder.removeEventListener('stop', finalizeRecordedVideo);
            if (mediaRecorder.state !== 'inactive') mediaRecorder.stop();
        } catch (_) {}
    }
    mediaRecorder = null;
    recordingFinalizing = false;
    stopCameraStream();
    uploadSession = null;
    resetChunkUploader();
    clearRecordedPreview();
    uploadProcessing.hidden = true;
    recordingBadge.hidden = true;
    switchCameraButton.hidden = true;
    recordToggleButton.hidden = true;
    playRecordingButton.hidden = true;
    startCameraButton.hidden = !showCameraButton;
    cameraPlaceholder.hidden = false;
    clearCameraError();

    if (previousUpload && previousUpload.status !== 'CONSUMED') {
        deleteUploadSession(previousUpload).catch(() => {});
    }
}

async function discardCurrentUpload() {
    const previous = uploadSession;
    uploadSession = null;
    resetChunkUploader();
    uploadProcessing.hidden = true;
    offerFields.hidden = true;
    submitButton.hidden = true;
    clearRecordedPreview();
    if (previous && previous.status !== 'CONSUMED') {
        await deleteUploadSession(previous).catch(() => {});
    }
}

async function deleteUploadSession(session) {
    if (!session?.id || !session?.uploadToken) return;
    const response = await fetch(`/bitrix/mobile/uploads/${encodeURIComponent(session.id)}`, {
        method: 'DELETE',
        headers: {'X-Upload-Token': session.uploadToken}
    });
    if (!response.ok && response.status !== 404 && response.status !== 409) {
        const data = await readJson(response);
        throw new Error(data.message || 'Не удалось удалить предыдущую запись');
    }
}

function resetChunkUploader() {
    uploadGeneration++;
    uploadFailure = null;
    uploadQueue = [];
    activeChunkUploads = 0;
    nextChunkSequence = 0;
    uploadedChunkCount = 0;
    uploadedChunkBytes = 0;
    finalChunkCount = 0;
    activeUploadControllers.forEach((controller) => {
        try { controller.abort(); } catch (_) {}
    });
    activeUploadControllers.clear();
    uploadDrainWaiters.splice(0).forEach((waiter) => waiter.reject(new Error('Загрузка отменена')));
    setUploadProgress(0);
}

function renderUploadSaveProgress() {
    if (!uploadProcessing || uploadProcessing.hidden) return;
    const total = Math.max(finalChunkCount, nextChunkSequence, uploadedChunkCount);
    const percent = total > 0
        ? Math.max(0, Math.min(99, Math.round((uploadedChunkCount / total) * 100)))
        : 0;
    setUploadProgress(percent);
}

function setUploadProgress(percent) {
    const safe = Math.max(0, Math.min(100, Number(percent) || 0));
    uploadProgressBar.style.width = safe + '%';
    uploadProgressText.textContent = Math.round(safe) + '%';
}

function waitForChunkUploads(timeoutMs) {
    if (uploadFailure) return Promise.reject(uploadFailure);
    if (uploadQueue.length === 0 && activeChunkUploads === 0) return Promise.resolve();
    return new Promise((resolve, reject) => {
        const waiter = {resolve, reject, timeout: null};
        waiter.timeout = setTimeout(() => {
            uploadDrainWaiters = uploadDrainWaiters.filter((item) => item !== waiter);
            reject(new Error('Не удалось дождаться завершения загрузки видео'));
        }, timeoutMs);
        uploadDrainWaiters.push(waiter);
    });
}

function notifyUploadDrainWaiters() {
    if (uploadQueue.length !== 0 || activeChunkUploads !== 0) return;
    const waiters = uploadDrainWaiters.splice(0);
    waiters.forEach((waiter) => {
        clearTimeout(waiter.timeout);
        if (uploadFailure) waiter.reject(uploadFailure);
        else waiter.resolve();
    });
}

function clearRecordedPreview() {
    recordedPreviewUrl = null;
    try { cameraPreview.pause(); } catch (_) {}
    cameraPreview.removeAttribute('src');
    cameraPreview.srcObject = null;
    cameraPreview.muted = true;
    cameraPreview.autoplay = true;
    try { cameraPreview.load(); } catch (_) {}
    playRecordingButton.hidden = true;
    updatePlayButtonState();
}

function toggleRecordedPlayback() {
    if (!recordedPreviewUrl || cameraPreview.srcObject) return;
    if (cameraPreview.paused || cameraPreview.ended) {
        if (cameraPreview.ended) cameraPreview.currentTime = 0;
        cameraPreview.play().catch((error) => setCameraError(error.message || 'Не удалось воспроизвести запись'));
    } else {
        cameraPreview.pause();
    }
    updatePlayButtonState();
}

function updatePlayButtonState() {
    const playing = !!recordedPreviewUrl && !cameraPreview.paused && !cameraPreview.ended;
    playGlyph.textContent = playing ? 'Ⅱ' : '▶';
    playRecordingButton.setAttribute('aria-label', playing ? 'Пауза' : 'Воспроизвести запись');
    playRecordingButton.title = playing ? 'Пауза' : 'Воспроизвести запись';
}

function updateRecordButtonState(recording) {
    recordToggleButton.classList.toggle('is-recording', !!recording);
    recordToggleButton.setAttribute('aria-label', recording ? 'Остановить запись' : 'Начать запись');
    recordToggleButton.title = recording ? 'Остановить запись' : 'Начать запись';
}

function supportsEmbeddedRecording() {
    return !!(navigator.mediaDevices
        && typeof navigator.mediaDevices.getUserMedia === 'function'
        && typeof window.MediaRecorder !== 'undefined');
}

function chooseRecorderMimeType() {
    if (typeof window.MediaRecorder === 'undefined'
        || typeof MediaRecorder.isTypeSupported !== 'function') return '';
    const candidates = [
        'video/mp4;codecs=avc1.42E01E,mp4a.40.2',
        'video/mp4',
        'video/webm;codecs=vp8,opus',
        'video/webm'
    ];
    return candidates.find((type) => MediaRecorder.isTypeSupported(type)) || '';
}

function isRecordingActive() {
    return !!mediaRecorder && mediaRecorder.state !== 'inactive';
}

function stopCameraStream() {
    if (cameraStream) {
        cameraStream.getTracks().forEach((track) => track.stop());
        cameraStream = null;
    }
    cameraPreview.srcObject = null;
}

function cameraErrorMessage(error) {
    const name = error?.name || '';
    if (name === 'NotAllowedError' || name === 'PermissionDeniedError' || name === 'SecurityError') {
        return 'Доступ к камере или микрофону запрещён. Разрешите их для Bitrix24/браузера и нажмите «Включить камеру» ещё раз.';
    }
    if (name === 'NotFoundError' || name === 'DevicesNotFoundError') {
        return 'Камера или микрофон не найдены на компьютере.';
    }
    if (name === 'NotReadableError' || name === 'TrackStartError') {
        return 'Камера или микрофон сейчас заняты другим приложением либо недоступны.';
    }
    return 'Не удалось открыть камеру: ' + (error?.message || name || 'неизвестная ошибка');
}

function setCameraError(message) {
    cameraError.hidden = !message;
    cameraError.textContent = message || '';
}

function clearCameraError() {
    setCameraError(null);
}

function renderTimer() {
    if (recordingStartedAt == null) return;
    const seconds = Math.floor(Math.max(0, performance.now() - recordingStartedAt) / 1000);
    const minutes = String(Math.floor(seconds / 60)).padStart(2, '0');
    const rest = String(seconds % 60).padStart(2, '0');
    recordingTimer.textContent = `${minutes}:${rest}`;
}

function startTimer() {
    stopTimer(true);
    recordingStartedAt = performance.now();
    renderTimer();
    timerHandle = setInterval(renderTimer, 250);
}

function stopTimer(resetDisplay = false) {
    if (recordingStartedAt != null && !resetDisplay) renderTimer();
    if (timerHandle != null) clearInterval(timerHandle);
    timerHandle = null;
    recordingStartedAt = null;
    if (resetDisplay) recordingTimer.textContent = '00:00';
}

async function checkStatus() {
    if (!activeOfferId) return;
    try {
        const response = await fetch('/api/video-offers/' + activeOfferId, {cache: 'no-store'});
        const data = await readJson(response);
        if (!response.ok) throw new Error(data.message || 'Не удалось проверить статус');
        renderOffer(data);

        const deliveryFinished = data.bitrixDeliveryStatus !== 'PENDING'
            && data.bitrixDeliveryStatus !== 'SENDING';
        if (data.status === 'ERROR' || data.status === 'CANCELLED'
            || (data.status === 'READY' && deliveryFinished)) {
            clearInterval(pollTimer);
            submitButton.disabled = false;
            submitButton.textContent = 'Сформировать ещё один';
        }
    } catch (error) {
        finishWithError(error.message);
    }
}

function renderOffer(data) {
    const percent = Math.max(0, Math.min(100, data.progressPercent || 0));
    const linkMode = sourceModeInput.value === 'LINK';
    const statusText = {
        QUEUED: 'Задача поставлена в очередь',
        PREPARING: linkMode ? 'Скачиваем запись Контур.Толка' : 'Подготавливаем запись',
        READY: 'Публичная страница готова',
        ERROR: data.errorMessage || 'Ошибка подготовки видео',
        CANCELLED: 'Подготовка отменена'
    };

    renderProgress(percent, statusText[data.status] || data.status);

    if (data.status === 'READY') {
        publicLink.href = data.publicUrl + '?preview=1';
        readyResult.hidden = false;
        if (data.bitrixDeliveryStatus === 'DELIVERED') {
            deliveryStatus.textContent = 'Ссылка добавлена в таймлайн текущей карточки.';
            readyMessage.textContent = 'Ссылка добавлена в таймлайн. ' + viewGoalMessage(data.viewNotificationGoal);
        } else if (data.bitrixDeliveryStatus === 'ERROR') {
            deliveryStatus.textContent = 'Видео готово, но Bitrix24 не принял комментарий.';
            readyMessage.textContent = data.bitrixDeliveryError
                ? 'Страница готова. Ошибка добавления в карточку: ' + data.bitrixDeliveryError
                : 'Страница готова, но ссылку не удалось добавить в карточку.';
        } else {
            deliveryStatus.textContent = 'Добавляем ссылку в таймлайн карточки…';
            readyMessage.textContent = 'Публичная страница готова. Завершаем запись ссылки в Bitrix24.';
        }
    }

    if (data.status === 'ERROR') setError(data.errorMessage || 'Не удалось подготовить видеооффер');
    fitWindow();
}

function renderProgress(percent, text) {
    processing.hidden = false;
    progressBar.style.width = percent + '%';
    processingPercent.textContent = percent + '%';
    processingStatus.textContent = text;
}

function finishWithError(message) {
    clearInterval(pollTimer);
    setError(message);
    submitButton.disabled = false;
    submitButton.textContent = 'Сформировать видеооффер';
    fitWindow();
}

function setError(message) {
    formError.hidden = !message;
    formError.textContent = message || '';
}

async function fetchWithTimeout(url, options, timeoutMs) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    try {
        return await fetch(url, {...options, signal: controller.signal});
    } catch (error) {
        if (error?.name === 'AbortError') throw new Error('Сервер слишком долго не отвечает');
        throw error;
    } finally {
        clearTimeout(timer);
    }
}

async function readJson(response) {
    const text = await response.text();
    if (!text) return {};
    try { return JSON.parse(text); }
    catch (_) { return {message: text}; }
}

function sleep(ms) {
    return new Promise((resolve) => setTimeout(resolve, ms));
}

function initializeBitrixFrame() {
    if (typeof BX24 === 'undefined') return;
    BX24.init(function () {
        BX24.resizeWindow(840, 820);
        BX24.fitWindow();
    });
}

function fitWindow() {
    if (typeof BX24 === 'undefined') return;
    try { BX24.fitWindow(); } catch (_) {}
}

function viewGoalMessage(goal) {
    const messages = {
        ONE_MINUTE: 'Уведомим, когда клиент посмотрит одну минуту видео.',
        HALF: 'Уведомим, когда клиент досмотрит видео до середины.',
        COMPLETED: 'Уведомим, когда клиент досмотрит видео целиком.',
        NONE: 'Уведомление о просмотре отключено.'
    };
    return messages[goal] || '';
}
