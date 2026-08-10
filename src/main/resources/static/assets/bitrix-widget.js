const form = document.getElementById('bitrix-offer-form');
const contextToken = document.getElementById('context-token').value;
const sourceModeInput = document.getElementById('source-mode');
const sourcePicker = document.getElementById('source-picker');
const linkSourceSection = document.getElementById('link-source-section');
const recordSourceSection = document.getElementById('record-source-section');
const fileSourceSection = document.getElementById('file-source-section');
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
const cameraStage = document.getElementById('camera-stage');
const cameraPlaceholderIcon = document.getElementById('camera-placeholder-icon');
const cameraPlaceholderTitle = document.getElementById('camera-placeholder-title');
const cameraPlaceholderText = document.getElementById('camera-placeholder-text');
const captureModePicker = document.getElementById('capture-mode-picker');
const systemAudioOption = document.getElementById('system-audio-option');
const captureSystemAudio = document.getElementById('capture-system-audio');
const captureMicrophone = document.getElementById('capture-microphone');
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
const screenHelperState = document.getElementById('screen-helper-state');
const screenHelperText = document.getElementById('screen-helper-text');

const fileInput = document.getElementById('video-file-input');
const chooseFileButton = document.getElementById('choose-file-button');
const replaceFileButton = document.getElementById('replace-file-button');
const selectedFileCard = document.getElementById('selected-file-card');
const selectedFileName = document.getElementById('selected-file-name');
const selectedFileMeta = document.getElementById('selected-file-meta');
const fileUploadProcessing = document.getElementById('file-upload-processing');
const fileUploadStatus = document.getElementById('file-upload-status');
const fileUploadProgressText = document.getElementById('file-upload-progress-text');
const fileUploadProgressBar = document.getElementById('file-upload-progress-bar');
const filePreview = document.getElementById('file-preview');

const MEDIA_CHUNK_INTERVAL_MS = 2000;
const MAX_PARALLEL_UPLOADS = 4;
const CHUNK_UPLOAD_TIMEOUT_MS = 30000;
const UPLOAD_DRAIN_TIMEOUT_MS = 90000;
const FILE_CHUNK_BYTES = 4 * 1024 * 1024;
const MAX_FILE_BYTES = 100 * 1024 * 1024;
const SCREEN_HELPER_CHANNEL = 'video-offer-screen';

let activeOfferId = null;
let pollTimer = null;
let finalUploadSession = null;

let captureMode = 'CAMERA';
let cameraStream = null;
let cameraDevices = [];
let activeCameraIndex = 0;
let currentCameraSegment = null;
let captureSwitching = false;

let recordingSessionActive = false;
let recordingStartedAt = null;
let timerHandle = null;
let currentSegment = null;
let nextSegmentIndex = 0;
let segmentPromises = [];
let recordedPreviewUrl = null;

let screenHelper = null;
let screenHelperReady = false;
let screenCaptureReady = false;
let screenReadyWaiters = [];
let screenStartWaiters = new Map();
let screenStopWaiters = new Map();

initializeBitrixFrame();
initializeSourcePicker();
initializeRecorder();
initializeFileUpload();
initializeScreenHelperMessages();

form.addEventListener('submit', handleOfferSubmit);

async function handleOfferSubmit(event) {
    event.preventDefault();
    clearInterval(pollTimer);
    setError(null);
    readyResult.hidden = true;
    processing.hidden = false;
    submitButton.disabled = true;
    submitButton.textContent = 'Создаём…';
    renderProgress(sourceModeInput.value === 'LINK' ? 0 : 100, 'Создаём видеооффер…');
    fitWindow();

    try {
        let response;
        if (sourceModeInput.value === 'LINK') {
            const url = recordingUrlInput.value.trim();
            if (!url) throw new Error('Вставьте ссылку на видео.');
            response = await fetch('/bitrix/video-offers', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({
                    contextToken,
                    recordingUrl: url,
                    accompanyingText: document.getElementById('accompanying-text').value.trim() || null,
                    viewNotificationGoal: document.getElementById('view-notification-goal').value
                })
            });
        } else {
            if (!finalUploadSession || finalUploadSession.status !== 'READY') {
                throw new Error(sourceModeInput.value === 'FILE'
                    ? 'Сначала выберите и дождитесь подготовки видеофайла.'
                    : 'Сначала запишите и дождитесь подготовки видео.');
            }
            response = await fetch(`/bitrix/mobile/uploads/${encodeURIComponent(finalUploadSession.id)}/offer`, {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({
                    uploadToken: finalUploadSession.uploadToken,
                    accompanyingText: document.getElementById('accompanying-text').value.trim() || null,
                    viewNotificationGoal: document.getElementById('view-notification-goal').value
                })
            });
        }

        const data = await readJson(response);
        if (!response.ok) throw new Error(data.message || 'Не удалось создать видеооффер');
        activeOfferId = data.id;
        renderOffer(data);
        const deliveryFinished = data.bitrixDeliveryStatus !== 'PENDING' && data.bitrixDeliveryStatus !== 'SENDING';
        if (data.status === 'READY' && deliveryFinished) {
            submitButton.disabled = false;
            submitButton.textContent = 'Сформировать ещё один';
        } else {
            pollTimer = setInterval(checkStatus, 1000);
            await checkStatus();
        }
    } catch (error) {
        finishWithError(error.message || 'Не удалось создать видеооффер');
    }
}

function initializeSourcePicker() {
    sourcePicker.querySelectorAll('[data-source]').forEach(button => {
        button.addEventListener('click', async () => {
            const mode = button.dataset.source;
            if (!mode || mode === sourceModeInput.value || recordingSessionActive) return;
            await switchSourceMode(mode);
        });
    });
}

async function switchSourceMode(mode) {
    await resetTransientMedia();
    sourceModeInput.value = mode;
    sourcePicker.querySelectorAll('[data-source]').forEach(button => {
        const active = button.dataset.source === mode;
        button.classList.toggle('is-active', active);
        button.setAttribute('aria-pressed', active ? 'true' : 'false');
    });
    clearInterval(pollTimer);
    activeOfferId = null;
    processing.hidden = true;
    readyResult.hidden = true;
    setError(null);

    linkSourceSection.hidden = mode !== 'LINK';
    recordSourceSection.hidden = mode !== 'RECORD';
    fileSourceSection.hidden = mode !== 'FILE';
    recordingUrlInput.required = mode === 'LINK';
    offerFields.hidden = mode !== 'LINK';
    submitButton.hidden = mode !== 'LINK';
    submitButton.disabled = false;
    submitButton.textContent = 'Сформировать видеооффер';

    if (mode === 'RECORD') {
        renderCaptureModeUi();
        if (captureMode === 'CAMERA') await ensureCameraCapture(false);
        else renderScreenPlaceholder();
    }
    fitWindow();
}

function initializeRecorder() {
    startCameraButton.addEventListener('click', async () => {
        if (captureMode === 'SCREEN') await openScreenCaptureHelper(true);
        else await ensureCameraCapture(false);
    });
    switchCameraButton.addEventListener('click', switchCamera);
    recordToggleButton.addEventListener('click', toggleRecordingSession);
    playRecordingButton.addEventListener('click', toggleRecordedPlayback);
    cameraPreview.addEventListener('play', updatePlayButtonState);
    cameraPreview.addEventListener('pause', updatePlayButtonState);
    cameraPreview.addEventListener('ended', updatePlayButtonState);

    captureModePicker.querySelectorAll('[data-capture-mode]').forEach(button => {
        button.addEventListener('click', async () => {
            const next = button.dataset.captureMode;
            if (!next || next === captureMode || captureSwitching) return;
            if (recordingSessionActive) await switchRecordingSource(next);
            else await selectCaptureMode(next);
        });
    });
    captureMicrophone.addEventListener('change', handleCaptureOptionChange);
    captureSystemAudio.addEventListener('change', handleCaptureOptionChange);
    renderCaptureModeUi();
}

async function selectCaptureMode(mode) {
    captureMode = mode === 'SCREEN' ? 'SCREEN' : 'CAMERA';
    clearCameraError();
    clearRecordedPreview();
    renderCaptureModeUi();
    if (captureMode === 'CAMERA') {
        await ensureCameraCapture(false);
    } else {
        stopCameraCapture();
        renderScreenPlaceholder();
        await openScreenCaptureHelper(true);
    }
    fitWindow();
}

async function handleCaptureOptionChange() {
    if (recordingSessionActive) return;
    clearCameraError();
    if (captureMode === 'CAMERA') {
        await ensureCameraCapture(false);
    } else if (screenCaptureReady || screenHelperReady) {
        screenCaptureReady = false;
        sendScreenMessage('DISCARD_CAPTURE');
        renderScreenPlaceholder('Настройки звука изменены. В окне захвата выберите экран заново.');
    }
}

function renderCaptureModeUi() {
    captureModePicker.querySelectorAll('[data-capture-mode]').forEach(button => {
        const active = button.dataset.captureMode === captureMode;
        button.classList.toggle('is-active', active);
        button.setAttribute('aria-pressed', active ? 'true' : 'false');
        button.disabled = captureSwitching;
    });
    const screen = captureMode === 'SCREEN';
    systemAudioOption.hidden = !screen;
    cameraStage.classList.toggle('is-screen', screen);
    switchCameraButton.hidden = screen || cameraDevices.length < 2;
    startCameraButton.textContent = screen ? 'Выбрать экран' : 'Включить камеру';
    captureMicrophone.disabled = recordingSessionActive;
    captureSystemAudio.disabled = recordingSessionActive;
    if (screen) renderScreenPlaceholder();
}

function renderScreenPlaceholder(message) {
    cameraPreview.pause?.();
    cameraPreview.srcObject = null;
    cameraPreview.removeAttribute('src');
    cameraPreview.hidden = true;
    cameraPlaceholder.hidden = false;
    cameraPlaceholderIcon.textContent = '▣';
    cameraPlaceholderTitle.textContent = screenCaptureReady ? 'Экран выбран' : 'Экран не выбран';
    cameraPlaceholderText.textContent = message || (screenCaptureReady
        ? 'Захват готов. Управление экраном находится в отдельном окне.'
        : 'Откройте окно захвата и выберите экран.');
    startCameraButton.hidden = screenCaptureReady || recordingSessionActive;
    recordToggleButton.hidden = !screenCaptureReady && !recordingSessionActive;
    screenHelperState.hidden = !screenHelperReady;
    screenHelperText.textContent = screenCaptureReady
        ? 'Экран выбран. Окно захвата можно переместить в удобное место.'
        : 'Окно захвата открыто. Выберите экран в нём.';
}

async function ensureCameraCapture(switching) {
    if (sourceModeInput.value !== 'RECORD') return false;
    if (!navigator.mediaDevices?.getUserMedia || typeof window.MediaRecorder === 'undefined') {
        setCameraError('Этот браузер не поддерживает запись с камеры.');
        return false;
    }
    clearCameraError();
    startCameraButton.disabled = true;
    startCameraButton.textContent = switching ? 'Переключаем…' : 'Включаем…';
    try {
        stopCameraCapture();
        clearRecordedPreview();
        let video = {width: {ideal: 1280, max: 1280}, height: {ideal: 720, max: 1280}, frameRate: {ideal: 30, max: 30}};
        if (cameraDevices[activeCameraIndex]?.deviceId) {
            video = {...video, deviceId: {exact: cameraDevices[activeCameraIndex].deviceId}};
        }
        const videoStream = await navigator.mediaDevices.getUserMedia({video, audio: false});
        let micStream = null;
        if (captureMicrophone.checked) {
            micStream = await navigator.mediaDevices.getUserMedia({
                video: false,
                audio: {echoCancellation: true, noiseSuppression: true, autoGainControl: true, channelCount: 1}
            });
        }
        cameraStream = new MediaStream();
        const videoTrack = videoStream.getVideoTracks()[0];
        if (!videoTrack) throw new Error('Камера не передала видеодорожку.');
        cameraStream.addTrack(videoTrack);
        const micTrack = micStream?.getAudioTracks?.()[0];
        if (micTrack) cameraStream.addTrack(micTrack);
        // Keep ownership on the composed stream; tracks are the same objects as source streams.
        cameraStream._sourceStreams = [videoStream, micStream].filter(Boolean);
        cameraPreview.hidden = false;
        cameraPlaceholder.hidden = true;
        cameraPreview.muted = true;
        cameraPreview.autoplay = true;
        cameraPreview.srcObject = cameraStream;
        await cameraPreview.play();
        await refreshCameraDevices();
        const id = videoTrack.getSettings?.().deviceId;
        const index = cameraDevices.findIndex(device => device.deviceId === id);
        if (index >= 0) activeCameraIndex = index;
        switchCameraButton.hidden = cameraDevices.length < 2 || captureMode !== 'CAMERA';
        switchCameraButton.disabled = recordingSessionActive;
        recordToggleButton.hidden = false;
        startCameraButton.hidden = true;
        updateRecordButtonState(recordingSessionActive);
        return true;
    } catch (error) {
        stopCameraCapture();
        showCameraPlaceholder();
        setCameraError(cameraErrorMessage(error));
        return false;
    } finally {
        startCameraButton.disabled = false;
        startCameraButton.textContent = captureMode === 'SCREEN' ? 'Выбрать экран' : 'Включить камеру';
        fitWindow();
    }
}

function showCameraPlaceholder() {
    cameraPreview.hidden = true;
    cameraPlaceholder.hidden = false;
    cameraPlaceholderIcon.textContent = '●';
    cameraPlaceholderTitle.textContent = 'Камера выключена';
    cameraPlaceholderText.textContent = captureMicrophone.checked
        ? 'Разрешите доступ к камере и микрофону.' : 'Разрешите доступ к камере.';
    startCameraButton.hidden = recordingSessionActive;
    recordToggleButton.hidden = true;
}

async function refreshCameraDevices() {
    try {
        const devices = await navigator.mediaDevices.enumerateDevices();
        cameraDevices = devices.filter(device => device.kind === 'videoinput');
    } catch (_) { cameraDevices = []; }
}

async function switchCamera() {
    if (recordingSessionActive || captureMode !== 'CAMERA' || cameraDevices.length < 2) return;
    activeCameraIndex = (activeCameraIndex + 1) % cameraDevices.length;
    await ensureCameraCapture(true);
}

function stopCameraCapture() {
    if (cameraStream) {
        try { cameraStream.getTracks().forEach(track => track.stop()); } catch (_) { }
        const sources = cameraStream._sourceStreams || [];
        sources.forEach(stream => { try { stream.getTracks().forEach(track => track.stop()); } catch (_) { } });
    }
    cameraStream = null;
    if (cameraPreview.srcObject) cameraPreview.srcObject = null;
}

async function toggleRecordingSession() {
    if (captureSwitching) return;
    if (recordingSessionActive) await stopRecordingSession();
    else await startRecordingSession();
}

async function startRecordingSession() {
    clearCameraError();
    setError(null);
    await discardFinalUpload();
    clearRecordedPreview();
    segmentPromises = [];
    nextSegmentIndex = 0;
    currentSegment = null;

    try {
        if (captureMode === 'CAMERA') {
            if (!cameraStream && !(await ensureCameraCapture(false))) return;
        } else {
            if (!screenCaptureReady) {
                await openScreenCaptureHelper(true);
                await waitForScreenCaptureReady(5 * 60 * 1000);
            }
        }

        recordingSessionActive = true;
        startSessionTimer();
        setRecordingUi(true);
        const index = nextSegmentIndex++;
        if (captureMode === 'CAMERA') {
            await startCameraSegment(index);
            currentSegment = {mode: 'CAMERA', index};
        } else {
            await startScreenSegment(index);
            currentSegment = {mode: 'SCREEN', index};
        }
    } catch (error) {
        recordingSessionActive = false;
        stopSessionTimer(true);
        setRecordingUi(false);
        setCameraError(error.message || 'Не удалось начать запись');
    }
    fitWindow();
}

async function switchRecordingSource(nextMode) {
    nextMode = nextMode === 'SCREEN' ? 'SCREEN' : 'CAMERA';
    if (!recordingSessionActive || !currentSegment || nextMode === currentSegment.mode || captureSwitching) return;
    captureSwitching = true;
    renderCaptureModeUi();
    setCameraError(null);
    try {
        if (nextMode === 'SCREEN') {
            if (!screenCaptureReady) {
                await openScreenCaptureHelper(true);
                await waitForScreenCaptureReady(5 * 60 * 1000);
            }
            const newIndex = nextSegmentIndex++;
            // Start the new source first, then finish the previous one. This keeps the visible recording continuous.
            await startScreenSegment(newIndex);
            const old = currentSegment;
            segmentPromises[old.index] = stopCameraSegment();
            segmentPromises[old.index].finally(() => stopCameraCapture());
            currentSegment = {mode: 'SCREEN', index: newIndex};
            captureMode = 'SCREEN';
            renderCaptureModeUi();
            renderScreenPlaceholder('Запись продолжается с экрана.');
        } else {
            if (!cameraStream && !(await ensureCameraCapture(false))) {
                throw new Error('Не удалось подготовить камеру для переключения.');
            }
            const newIndex = nextSegmentIndex++;
            await startCameraSegment(newIndex);
            const old = currentSegment;
            segmentPromises[old.index] = stopScreenSegment(old.index);
            currentSegment = {mode: 'CAMERA', index: newIndex};
            captureMode = 'CAMERA';
            renderCaptureModeUi();
        }
    } catch (error) {
        setCameraError(error.message || 'Не удалось переключить источник записи');
    } finally {
        captureSwitching = false;
        renderCaptureModeUi();
        fitWindow();
    }
}

async function stopRecordingSession() {
    if (!recordingSessionActive || !currentSegment) return;
    recordToggleButton.disabled = true;
    captureSwitching = true;
    uploadProcessing.hidden = false;
    uploadStatus.textContent = 'Сохраняем запись…';
    uploadProgressText.textContent = '';
    uploadProgressBar.style.width = '45%';
    stopSessionTimer(false);
    updateRecordButtonState(false);

    try {
        const current = currentSegment;
        if (current.mode === 'CAMERA') segmentPromises[current.index] = stopCameraSegment();
        else segmentPromises[current.index] = stopScreenSegment(current.index);
        currentSegment = null;

        const results = await Promise.all(segmentPromises.filter(Boolean));
        if (!results.length) throw new Error('Запись не содержит видеоданных.');
        uploadStatus.textContent = results.length > 1 ? 'Собираем единое видео…' : 'Подготавливаем видео…';
        uploadProgressBar.style.width = '80%';
        finalUploadSession = results.length === 1 ? results[0] : await mergeRecordingSegments(results);
        uploadProgressBar.style.width = '100%';
        showNormalizedRecording(finalUploadSession);
    } catch (error) {
        uploadProcessing.hidden = true;
        setCameraError(error.message || 'Не удалось сохранить запись');
        offerFields.hidden = true;
        submitButton.hidden = true;
    } finally {
        recordingSessionActive = false;
        captureSwitching = false;
        recordToggleButton.disabled = false;
        stopCameraCapture();
        setRecordingUi(false);
        renderCaptureModeUi();
        fitWindow();
    }
}

function setRecordingUi(recording) {
    recordingBadge.hidden = !recording;
    updateRecordButtonState(recording);
    sourcePicker.querySelectorAll('button').forEach(button => button.disabled = recording);
    captureMicrophone.disabled = recording;
    captureSystemAudio.disabled = recording;
    switchCameraButton.disabled = recording;
    playRecordingButton.hidden = recording || !recordedPreviewUrl;
    recordToggleButton.hidden = false;
    if (recording) startCameraButton.hidden = true;
    captureModePicker.querySelectorAll('button').forEach(button => button.disabled = captureSwitching);
}

async function startCameraSegment(index) {
    if (!cameraStream?.getVideoTracks?.().length) throw new Error('Камера не готова к записи.');
    if (currentCameraSegment) throw new Error('Камера уже записывает сегмент.');
    const mimeType = chooseRecorderMimeType();
    const recorder = mimeType
        ? new MediaRecorder(cameraStream, {mimeType, videoBitsPerSecond: 1800000, audioBitsPerSecond: 96000})
        : new MediaRecorder(cameraStream, {videoBitsPerSecond: 1800000, audioBitsPerSecond: 96000});
    const uploader = new ChunkUploader(contextToken, recorder.mimeType || mimeType || 'video/webm', 'RECORDING', null);
    await uploader.init();
    recorder.addEventListener('dataavailable', event => {
        if (event.data?.size > 0) uploader.enqueue(event.data);
    });
    const started = new Promise((resolve, reject) => {
        recorder.addEventListener('start', resolve, {once: true});
        recorder.addEventListener('error', event => reject(event?.error || new Error('MediaRecorder не начал запись')), {once: true});
    });
    recorder.start(MEDIA_CHUNK_INTERVAL_MS);
    await started;
    currentCameraSegment = {index, recorder, uploader, stopPromise: null};
}

function stopCameraSegment() {
    const segment = currentCameraSegment;
    if (!segment) return Promise.reject(new Error('Сегмент камеры не найден.'));
    if (segment.stopPromise) return segment.stopPromise;
    currentCameraSegment = null;
    segment.stopPromise = new Promise((resolve, reject) => {
        segment.recorder.addEventListener('stop', async () => {
            try {
                const ready = await segment.uploader.finish();
                resolve(ready);
            } catch (error) { reject(error); }
        }, {once: true});
        segment.recorder.addEventListener('error', event => reject(event?.error || new Error('Ошибка записи камеры')), {once: true});
        try { segment.recorder.stop(); } catch (error) { reject(error); }
    });
    return segment.stopPromise;
}

async function mergeRecordingSegments(results) {
    const response = await fetchWithTimeout('/bitrix/mobile/uploads/merge', {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({
            contextToken,
            segments: results.map(item => ({uploadId: item.id, uploadToken: item.uploadToken}))
        })
    }, 30 * 60 * 1000);
    const data = await readJson(response);
    if (!response.ok) throw new Error(data.message || 'Не удалось объединить части записи');
    if (data.status !== 'READY') throw new Error(data.errorMessage || 'Объединённая запись не готова');
    return data;
}

function showNormalizedRecording(data) {
    uploadProcessing.hidden = true;
    finalUploadSession = data;
    recordedPreviewUrl = data.previewUrl || '';
    cameraPlaceholder.hidden = true;
    cameraPreview.hidden = false;
    cameraPreview.autoplay = false;
    cameraPreview.muted = false;
    cameraPreview.srcObject = null;
    cameraPreview.src = recordedPreviewUrl;
    cameraPreview.load();
    playRecordingButton.hidden = !recordedPreviewUrl;
    updatePlayButtonState();
    offerFields.hidden = false;
    submitButton.hidden = false;
    submitButton.disabled = false;
    submitButton.textContent = 'Сформировать видеооффер';
    recordToggleButton.hidden = false;
    updateRecordButtonState(false);
    clearCameraError();
}

function updateRecordButtonState(recording) {
    recordToggleButton.classList.toggle('is-recording', !!recording);
    recordToggleButton.setAttribute('aria-label', recording ? 'Остановить запись' : 'Начать новую запись');
    recordToggleButton.title = recording ? 'Остановить запись' : 'Начать новую запись';
}

function startSessionTimer() {
    recordingStartedAt = performance.now();
    renderSessionTimer();
    clearInterval(timerHandle);
    timerHandle = setInterval(renderSessionTimer, 250);
}
function stopSessionTimer(reset) {
    if (!reset) renderSessionTimer();
    clearInterval(timerHandle);
    timerHandle = null;
    if (reset) { recordingStartedAt = null; recordingTimer.textContent = '00:00'; }
}
function renderSessionTimer() {
    if (recordingStartedAt == null) return;
    const seconds = Math.floor((performance.now() - recordingStartedAt) / 1000);
    recordingTimer.textContent = `${String(Math.floor(seconds / 60)).padStart(2,'0')}:${String(seconds % 60).padStart(2,'0')}`;
}

function clearRecordedPreview() {
    recordedPreviewUrl = null;
    try { cameraPreview.pause(); } catch (_) { }
    if (!cameraPreview.srcObject) {
        cameraPreview.removeAttribute('src');
        try { cameraPreview.load(); } catch (_) { }
    }
    playRecordingButton.hidden = true;
    updatePlayButtonState();
}
function toggleRecordedPlayback() {
    if (!recordedPreviewUrl || cameraPreview.srcObject) return;
    if (cameraPreview.paused || cameraPreview.ended) {
        if (cameraPreview.ended) cameraPreview.currentTime = 0;
        cameraPreview.play().catch(error => setCameraError(error.message || 'Не удалось воспроизвести запись'));
    } else cameraPreview.pause();
    updatePlayButtonState();
}
function updatePlayButtonState() {
    const playing = !!recordedPreviewUrl && !cameraPreview.paused && !cameraPreview.ended;
    playGlyph.textContent = playing ? 'Ⅱ' : '▶';
    playRecordingButton.setAttribute('aria-label', playing ? 'Пауза' : 'Воспроизвести запись');
    playRecordingButton.title = playing ? 'Пауза' : 'Воспроизвести запись';
}

function initializeScreenHelperMessages() {
    window.addEventListener('message', event => {
        if (event.origin !== location.origin) return;
        const message = event.data || {};
        if (message.channel !== SCREEN_HELPER_CHANNEL) return;
        if (screenHelper && event.source !== screenHelper) return;
        switch (message.type) {
            case 'HELPER_READY':
                screenHelperReady = true;
                sendScreenInit();
                screenHelperState.hidden = false;
                screenHelperText.textContent = 'Окно захвата открыто. Выберите экран в нём.';
                break;
            case 'INITIALIZED':
                screenHelperReady = true;
                break;
            case 'CAPTURE_READY':
                screenCaptureReady = true;
                screenHelperState.hidden = false;
                screenHelperText.textContent = 'Экран выбран. Захват готов.';
                resolveScreenReadyWaiters();
                if (captureMode === 'SCREEN') renderScreenPlaceholder();
                break;
            case 'CAPTURE_ERROR':
                screenCaptureReady = false;
                rejectScreenReadyWaiters(new Error(message.message || 'Не удалось получить экран'));
                setCameraError(message.message || 'Не удалось получить экран');
                break;
            case 'SEGMENT_STARTED': {
                const waiter = screenStartWaiters.get(Number(message.segmentIndex));
                if (waiter) { clearTimeout(waiter.timer); screenStartWaiters.delete(Number(message.segmentIndex)); waiter.resolve(); }
                break;
            }
            case 'SEGMENT_READY': {
                const waiter = screenStopWaiters.get(Number(message.segmentIndex));
                if (waiter) { clearTimeout(waiter.timer); screenStopWaiters.delete(Number(message.segmentIndex)); waiter.resolve(message.upload); }
                break;
            }
            case 'SEGMENT_ERROR': {
                const waiter = screenStopWaiters.get(Number(message.segmentIndex));
                if (waiter) { clearTimeout(waiter.timer); screenStopWaiters.delete(Number(message.segmentIndex)); waiter.reject(new Error(message.message || 'Ошибка записи экрана')); }
                break;
            }
            case 'REQUEST_STOP_RECORDING':
                if (recordingSessionActive) stopRecordingSession();
                break;
            case 'CAPTURE_ENDED':
                screenCaptureReady = false;
                if (captureMode === 'SCREEN') renderScreenPlaceholder('Демонстрация экрана завершена.');
                break;
            case 'HELPER_CLOSED':
                screenHelperReady = false;
                screenCaptureReady = false;
                if (recordingSessionActive && currentSegment?.mode === 'SCREEN') {
                    setCameraError('Окно записи экрана было закрыто. Остановите запись и повторите попытку.');
                }
                break;
            case 'HELPER_ERROR':
                setCameraError(message.message || 'Ошибка окна записи экрана');
                break;
            default: break;
        }
        fitWindow();
    });
}

async function openScreenCaptureHelper(focus = true) {
    if (screenHelper && !screenHelper.closed) {
        sendScreenInit();
        if (focus) { try { screenHelper.focus(); } catch (_) { } }
        return screenHelper;
    }
    screenHelperReady = false;
    screenCaptureReady = false;
    screenHelper = window.open(
        '/bitrix/screen-recorder?v=021',
        'videoOfferScreenRecorder',
        'popup=yes,width=430,height=500,resizable=yes,scrollbars=no');
    if (!screenHelper) {
        throw new Error('Bitrix Desktop заблокировал окно записи экрана. Разрешите всплывающие окна для приложения.');
    }
    screenHelperState.hidden = false;
    screenHelperText.textContent = 'Открываем окно захвата экрана…';
    return screenHelper;
}
function sendScreenInit() {
    sendScreenMessage('INIT', {
        contextToken,
        systemAudio: captureSystemAudio.checked,
        microphone: captureMicrophone.checked
    });
}
function sendScreenMessage(type, payload = {}) {
    if (!screenHelper || screenHelper.closed) return false;
    try {
        screenHelper.postMessage({channel: SCREEN_HELPER_CHANNEL, type, ...payload}, location.origin);
        return true;
    } catch (_) { return false; }
}
function waitForScreenCaptureReady(timeoutMs) {
    if (screenCaptureReady) return Promise.resolve();
    return new Promise((resolve, reject) => {
        const waiter = {resolve, reject, timer: null};
        waiter.timer = setTimeout(() => {
            screenReadyWaiters = screenReadyWaiters.filter(item => item !== waiter);
            reject(new Error('Экран не был выбран. Выберите «Весь экран» в отдельном окне.'));
        }, timeoutMs);
        screenReadyWaiters.push(waiter);
    });
}
function resolveScreenReadyWaiters() {
    const items = screenReadyWaiters.splice(0);
    items.forEach(item => { clearTimeout(item.timer); item.resolve(); });
}
function rejectScreenReadyWaiters(error) {
    const items = screenReadyWaiters.splice(0);
    items.forEach(item => { clearTimeout(item.timer); item.reject(error); });
}
function startScreenSegment(index) {
    if (!screenCaptureReady) return Promise.reject(new Error('Экран не выбран.'));
    return new Promise((resolve, reject) => {
        const timer = setTimeout(() => { screenStartWaiters.delete(index); reject(new Error('Окно записи экрана не ответило.')); }, 15000);
        screenStartWaiters.set(index, {resolve, reject, timer});
        if (!sendScreenMessage('START_SEGMENT', {segmentIndex: index, sessionStartedAt: Date.now()})) {
            clearTimeout(timer); screenStartWaiters.delete(index); reject(new Error('Окно записи экрана закрыто.'));
        }
    });
}
function stopScreenSegment(index) {
    return new Promise((resolve, reject) => {
        const timer = setTimeout(() => { screenStopWaiters.delete(index); reject(new Error('Окно записи экрана не завершило сохранение.')); }, 2 * 60 * 1000);
        screenStopWaiters.set(index, {resolve, reject, timer});
        if (!sendScreenMessage('STOP_SEGMENT', {segmentIndex: index})) {
            clearTimeout(timer); screenStopWaiters.delete(index); reject(new Error('Окно записи экрана закрыто.'));
        }
    });
}

function initializeFileUpload() {
    chooseFileButton.addEventListener('click', () => fileInput.click());
    replaceFileButton.addEventListener('click', () => fileInput.click());
    fileInput.addEventListener('change', async () => {
        const file = fileInput.files?.[0];
        if (!file) return;
        await handleSelectedFile(file);
    });
}

async function handleSelectedFile(file) {
    setError(null);
    clearFilePreview();
    try {
        validateVideoFile(file);
        await discardFinalUpload();
        selectedFileCard.hidden = false;
        selectedFileName.textContent = file.name;
        selectedFileMeta.textContent = `${formatBytes(file.size)} · ${file.type || fileExtension(file.name).toUpperCase()}`;
        chooseFileButton.hidden = true;
        offerFields.hidden = true;
        submitButton.hidden = true;
        fileUploadProcessing.hidden = false;
        setFileProgress(0, 'Загружаем видео…');

        const uploader = new ChunkUploader(contextToken, file.type || mimeFromFileName(file.name), 'FILE', file.size, percent => {
            setFileProgress(Math.min(95, percent), `Загружаем видео ${percent}%`);
        });
        await uploader.init();
        finalUploadSession = await uploader.uploadFile(file, FILE_CHUNK_BYTES);
        setFileProgress(100, 'Видео готово');
        fileUploadProcessing.hidden = true;
        showFilePreview(finalUploadSession);
        offerFields.hidden = false;
        submitButton.hidden = false;
    } catch (error) {
        fileUploadProcessing.hidden = true;
        chooseFileButton.hidden = false;
        setError(error.message || 'Не удалось загрузить видеофайл');
    }
    fitWindow();
}

function validateVideoFile(file) {
    if (!file || file.size <= 0) throw new Error('Выбран пустой видеофайл.');
    if (file.size > MAX_FILE_BYTES) throw new Error('Размер видеофайла не должен превышать 100 МБ.');
    const extension = fileExtension(file.name);
    if (!['mp4','mov','webm','mkv','m4v'].includes(extension)) {
        throw new Error('Поддерживаются MP4, MOV, WebM, MKV и M4V.');
    }
}
function showFilePreview(data) {
    filePreview.hidden = false;
    filePreview.src = data.previewUrl || '';
    filePreview.load();
}
function clearFilePreview() {
    filePreview.pause?.();
    filePreview.removeAttribute('src');
    filePreview.hidden = true;
}
function setFileProgress(percent, text) {
    const normalized = Math.max(0, Math.min(100, Math.round(percent || 0)));
    fileUploadProgressBar.style.width = normalized + '%';
    fileUploadProgressText.textContent = normalized + '%';
    fileUploadStatus.textContent = text;
}

class ChunkUploader {
    constructor(contextTokenValue, mimeType, sourceKind, declaredSizeBytes, progressCallback) {
        this.contextToken = contextTokenValue;
        this.mimeType = mimeType || 'application/octet-stream';
        this.sourceKind = sourceKind || 'RECORDING';
        this.declaredSizeBytes = declaredSizeBytes || null;
        this.progressCallback = progressCallback || (() => {});
        this.session = null;
        this.queue = [];
        this.active = 0;
        this.failure = null;
        this.nextSequence = 0;
        this.completed = 0;
        this.totalExpected = 0;
        this.waiters = [];
        this.generation = 1;
    }
    async init() {
        const response = await fetch('/bitrix/mobile/uploads', {
            method: 'POST', headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({
                contextToken: this.contextToken,
                mimeType: this.mimeType,
                sourceKind: this.sourceKind,
                declaredSizeBytes: this.declaredSizeBytes
            })
        });
        const data = await readJson(response);
        if (!response.ok) throw new Error(data.message || 'Не удалось создать сессию загрузки');
        this.session = data;
        return data;
    }
    enqueue(blob) {
        if (!blob || blob.size <= 0) return;
        const sequence = this.nextSequence++;
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
                    this.completed++;
                    this.renderProgress();
                })
                .catch(error => { if (task.generation === this.generation && !this.failure) this.failure = error; })
                .finally(() => {
                    if (task.generation !== this.generation) return;
                    this.active = Math.max(0, this.active - 1);
                    this.pump();
                    this.notify();
                });
        }
        this.notify();
    }
    renderProgress() {
        const total = Math.max(this.totalExpected || this.nextSequence, 1);
        const percent = Math.max(0, Math.min(99, Math.round(this.completed * 100 / total)));
        this.progressCallback(percent);
    }
    waitDrain(timeoutMs = UPLOAD_DRAIN_TIMEOUT_MS) {
        if (this.failure) return Promise.reject(this.failure);
        if (!this.queue.length && this.active === 0) return Promise.resolve();
        return new Promise((resolve, reject) => {
            const waiter = {resolve, reject, timer: null};
            waiter.timer = setTimeout(() => {
                this.waiters = this.waiters.filter(item => item !== waiter);
                reject(new Error('Не удалось вовремя передать видео на сервер.'));
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
    async uploadChunk(blob, sequence) {
        let lastError;
        for (let attempt = 1; attempt <= 4; attempt++) {
            const controller = new AbortController();
            const timeout = setTimeout(() => controller.abort(), CHUNK_UPLOAD_TIMEOUT_MS);
            try {
                const response = await fetch(`/bitrix/mobile/uploads/${encodeURIComponent(this.session.id)}/chunks/${sequence}`, {
                    method: 'PUT',
                    headers: {'Content-Type': 'application/octet-stream', 'X-Upload-Token': this.session.uploadToken},
                    body: blob,
                    signal: controller.signal
                });
                const data = await readJson(response);
                if (!response.ok) throw new Error(data.message || 'Сервер не принял часть видео');
                return data;
            } catch (error) {
                lastError = error?.name === 'AbortError' ? new Error('Сервер слишком долго принимал видео') : error;
                if (attempt < 4) await sleep(Math.min(1600, attempt * 400));
            } finally { clearTimeout(timeout); }
        }
        throw lastError || new Error('Не удалось загрузить часть видео');
    }
    async finish() {
        this.totalExpected = this.nextSequence;
        await this.waitDrain();
        if (this.failure) throw this.failure;
        if (this.totalExpected <= 0) throw new Error('Видео не содержит данных.');
        const response = await fetchWithTimeout(
            `/bitrix/mobile/uploads/${encodeURIComponent(this.session.id)}/complete?chunkCount=${encodeURIComponent(this.totalExpected)}`,
            {method: 'POST', headers: {'X-Upload-Token': this.session.uploadToken}}, 20000);
        const data = await readJson(response);
        if (!response.ok) throw new Error(data.message || 'Не удалось завершить загрузку видео');
        this.session = data;
        return this.waitReady();
    }
    async waitReady() {
        const started = Date.now();
        while (Date.now() - started < 20 * 60 * 1000) {
            const response = await fetchWithTimeout(
                `/bitrix/mobile/uploads/${encodeURIComponent(this.session.id)}?uploadToken=${encodeURIComponent(this.session.uploadToken)}`,
                {cache: 'no-store'}, 10000);
            const data = await readJson(response);
            if (!response.ok) throw new Error(data.message || 'Не удалось проверить обработку видео');
            this.session = data;
            if (data.status === 'READY') return data;
            if (data.status === 'ERROR') throw new Error(data.errorMessage || 'Не удалось обработать видео');
            await sleep(250);
        }
        throw new Error('Обработка видео заняла слишком много времени');
    }
    async uploadFile(file, chunkBytes) {
        const count = Math.ceil(file.size / chunkBytes);
        this.totalExpected = count;
        for (let i = 0; i < count; i++) {
            this.enqueue(file.slice(i * chunkBytes, Math.min(file.size, (i + 1) * chunkBytes)));
        }
        return this.finish();
    }
}

async function discardFinalUpload() {
    const previous = finalUploadSession;
    finalUploadSession = null;
    if (!previous || previous.status === 'CONSUMED') return;
    try {
        await fetch(`/bitrix/mobile/uploads/${encodeURIComponent(previous.id)}`, {
            method: 'DELETE', headers: {'X-Upload-Token': previous.uploadToken}
        });
    } catch (_) { }
}

async function resetTransientMedia() {
    if (recordingSessionActive) return;
    stopCameraCapture();
    clearRecordedPreview();
    clearFilePreview();
    selectedFileCard.hidden = true;
    chooseFileButton.hidden = false;
    fileInput.value = '';
    fileUploadProcessing.hidden = true;
    uploadProcessing.hidden = true;
    offerFields.hidden = sourceModeInput.value !== 'LINK';
    await discardFinalUpload();
}

function cameraErrorMessage(error) {
    const name = error?.name || '';
    if (name === 'NotAllowedError' || name === 'PermissionDeniedError' || name === 'SecurityError') return 'Доступ к камере или микрофону запрещён. Разрешите его для Bitrix24 и повторите попытку.';
    if (name === 'NotFoundError' || name === 'DevicesNotFoundError') return 'Камера или микрофон не найдены на компьютере.';
    if (name === 'NotReadableError' || name === 'TrackStartError') return 'Камера или микрофон заняты другим приложением либо недоступны.';
    return error?.message || 'Не удалось открыть камеру.';
}
function setCameraError(message) { cameraError.hidden = !message; cameraError.textContent = message || ''; }
function clearCameraError() { setCameraError(null); }

async function checkStatus() {
    if (!activeOfferId) return;
    try {
        const response = await fetch('/api/video-offers/' + activeOfferId, {cache: 'no-store'});
        const data = await readJson(response);
        if (!response.ok) throw new Error(data.message || 'Не удалось проверить статус');
        renderOffer(data);
        const deliveryFinished = data.bitrixDeliveryStatus !== 'PENDING' && data.bitrixDeliveryStatus !== 'SENDING';
        if (data.status === 'ERROR' || data.status === 'CANCELLED' || (data.status === 'READY' && deliveryFinished)) {
            clearInterval(pollTimer);
            submitButton.disabled = false;
            submitButton.textContent = 'Сформировать ещё один';
        }
    } catch (error) { finishWithError(error.message); }
}

function renderOffer(data) {
    const percent = Math.max(0, Math.min(100, data.progressPercent || 0));
    const statusText = {
        QUEUED: 'Задача поставлена в очередь',
        PREPARING: sourceModeInput.value === 'LINK' ? 'Загружаем и подготавливаем видео по ссылке' : 'Подготавливаем видео',
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
            readyMessage.textContent = data.bitrixDeliveryError ? 'Страница готова. Ошибка добавления в карточку: ' + data.bitrixDeliveryError : 'Страница готова, но ссылку не удалось добавить в карточку.';
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
function setError(message) { formError.hidden = !message; formError.textContent = message || ''; }

function chooseRecorderMimeType() {
    if (typeof window.MediaRecorder === 'undefined' || typeof MediaRecorder.isTypeSupported !== 'function') return '';
    return ['video/mp4;codecs=avc1.42E01E,mp4a.40.2','video/mp4','video/webm;codecs=vp8,opus','video/webm']
        .find(type => MediaRecorder.isTypeSupported(type)) || '';
}
function fileExtension(name) { const match = String(name || '').toLowerCase().match(/\.([a-z0-9]+)$/); return match ? match[1] : ''; }
function mimeFromFileName(name) { return ({mp4:'video/mp4',mov:'video/quicktime',webm:'video/webm',mkv:'video/x-matroska',m4v:'video/x-m4v'})[fileExtension(name)] || 'application/octet-stream'; }
function formatBytes(bytes) { const n=Number(bytes)||0; if(n<1024)return n+' Б'; if(n<1024*1024)return (n/1024).toFixed(1)+' КБ'; return (n/1024/1024).toFixed(1)+' МБ'; }
async function fetchWithTimeout(url, options, timeoutMs) {
    const controller = new AbortController(); const timer = setTimeout(() => controller.abort(), timeoutMs);
    try { return await fetch(url, {...(options || {}), signal: controller.signal}); }
    catch (error) { if (error?.name === 'AbortError') throw new Error('Сервер слишком долго не отвечает'); throw error; }
    finally { clearTimeout(timer); }
}
async function readJson(response) { const text = await response.text(); if (!text) return {}; try { return JSON.parse(text); } catch (_) { return {message: text}; } }
function sleep(ms) { return new Promise(resolve => setTimeout(resolve, ms)); }

function initializeBitrixFrame() {
    if (typeof BX24 === 'undefined') return;
    BX24.init(function () { BX24.resizeWindow(860, 860); BX24.fitWindow(); });
}
function fitWindow() { if (typeof BX24 === 'undefined') return; try { BX24.fitWindow(); } catch (_) { } }
function viewGoalMessage(goal) {
    return ({ONE_MINUTE:'Уведомим, когда клиент посмотрит одну минуту видео.',HALF:'Уведомим, когда клиент досмотрит видео до середины.',COMPLETED:'Уведомим, когда клиент досмотрит видео целиком.',NONE:'Уведомление о просмотре отключено.'})[goal] || '';
}
