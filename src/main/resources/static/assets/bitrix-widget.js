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
const filePreviewFrame = document.getElementById('file-preview-frame');
const fileFullscreenButton = document.getElementById('file-fullscreen-button');
const recordFullscreenButton = document.getElementById('record-fullscreen-button');

const MEDIA_CHUNK_INTERVAL_MS = 2000;
const MAX_PARALLEL_UPLOADS = 4;
const CHUNK_UPLOAD_TIMEOUT_MS = 60000;
const UPLOAD_DRAIN_TIMEOUT_MS = 90000;
const FILE_CHUNK_BYTES = 4 * 1024 * 1024;
const MAX_FILE_BYTES = 100 * 1024 * 1024;
const SCREEN_AGENT_CHANNEL = 'video-offer-screen-agent-v1';
const SCREEN_AGENT_URL = '/bitrix/screen-capture?v=027';
const SCREEN_AGENT_PICKER_OUTER_WIDTH = 612;
const SCREEN_AGENT_PICKER_OUTER_HEIGHT = 614;
const SCREEN_AGENT_INITIAL_INNER_WIDTH = 610;
const SCREEN_AGENT_INITIAL_INNER_HEIGHT = 582;

let activeOfferId = null;
let pollTimer = null;
let finalUploadSession = null;
let activeFileUploader = null;
let fileUploadGeneration = 0;

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

let screenAgent = null;
let screenAgentId = null;
let screenAgentReady = false;
let screenAgentClosing = false;
let screenCaptureReady = false;
let screenCaptureRequestPending = false;
let screenPreviewStream = null;
let screenPreviewPeer = null;
let screenPreviewPendingCandidates = [];
let screenReadyWaiters = [];
let screenStartWaiters = new Map();
let screenStopWaiters = new Map();

initializeBitrixFrame();
initializeSourcePicker();
initializeRecorder();
initializeScreenAgentMessages();
initializeFileUpload();
initializeFullscreenControls();
reportDesktopEvent('CAPABILITIES', JSON.stringify({
    mediaDevices: !!navigator.mediaDevices,
    getDisplayMediaInIframe: !!navigator.mediaDevices?.getDisplayMedia,
    screenCaptureAgent: true,
    mediaRecorder: typeof window.MediaRecorder !== 'undefined',
    fullscreenEnabled: !!document.fullscreenEnabled,
    displayCapturePolicy: featureAllowed('display-capture')
}));

form.addEventListener('submit', handleOfferSubmit);

async function handleOfferSubmit(event) {
    event.preventDefault();
    clearInterval(pollTimer);
    setError(null);
    readyResult.hidden = true;
    processing.hidden = false;
    submitButton.disabled = true;
    submitButton.textContent = 'Создаём…';
    renderProgress(sourceModeInput.value === 'LINK' ? 0 : 100, 'Загрузка видео');
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
        else await ensureScreenCapture(true);
    }
    fitWindow();
}

function initializeRecorder() {
    startCameraButton.addEventListener('click', async () => {
        await ensureCameraCapture(false);
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
            if (!next || captureSwitching) return;
            if (next === captureMode) {
                if (!recordingSessionActive && next === 'SCREEN' && !screenCaptureReady) {
                    await ensureScreenCapture(true);
                }
                return;
            }
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
    if (captureMode === 'CAMERA') {
        closeScreenCaptureAgent();
        renderCaptureModeUi();
        await ensureCameraCapture(false);
    } else {
        stopCameraCapture();
        renderCaptureModeUi();
        renderScreenPlaceholder();
        await ensureScreenCapture(true);
    }
    fitWindow();
}

async function handleCaptureOptionChange() {
    if (recordingSessionActive) return;
    clearCameraError();
    if (captureMode === 'CAMERA') {
        await ensureCameraCapture(false);
    } else {
        closeScreenCaptureAgent();
        renderScreenPlaceholder();
        await ensureScreenCapture(true);
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
    startCameraButton.textContent = 'Включить камеру';
    startCameraButton.hidden = screen || recordingSessionActive || !!cameraStream || !!recordedPreviewUrl;
    captureMicrophone.disabled = recordingSessionActive;
    captureSystemAudio.disabled = recordingSessionActive;

    if (!recordedPreviewUrl && screen) {
        if (screenCaptureReady && screenPreviewStream) showScreenPreview();
        else renderScreenPlaceholder();
    }
}

function renderScreenPlaceholder(message) {
    if (recordedPreviewUrl) return;
    try { cameraPreview.pause(); } catch (_) { }
    cameraPreview.srcObject = null;
    cameraPreview.removeAttribute('src');
    cameraPreview.hidden = true;
    cameraPlaceholder.hidden = false;
    cameraPlaceholderIcon.textContent = '▣';
    cameraPlaceholderTitle.textContent = screenCaptureRequestPending
        ? 'Выбираем экран…'
        : (screenCaptureReady ? 'Экран выбран' : 'Экран не выбран');
    cameraPlaceholderText.textContent = message || (screenCaptureReady
        ? 'Экран готов к записи.'
        : 'Выберите экран, окно или вкладку.');
    startCameraButton.hidden = true;
    recordToggleButton.hidden = !screenCaptureReady && !recordingSessionActive;
}

function showScreenPreview() {
    if (!screenPreviewStream || recordedPreviewUrl || captureMode !== 'SCREEN') return;
    cameraPlaceholder.hidden = true;
    cameraPreview.hidden = false;
    cameraPreview.autoplay = true;
    cameraPreview.controls = false;
    cameraPreview.muted = true;
    cameraPreview.removeAttribute('src');
    cameraPreview.srcObject = screenPreviewStream;
    cameraPreview.play().catch(() => {});
    recordFullscreenButton.hidden = true;
    recordToggleButton.hidden = false;
}

async function ensureScreenCapture(fromUserGesture) {
    if (sourceModeInput.value !== 'RECORD') return false;
    if (typeof window.MediaRecorder === 'undefined') {
        setCameraError('Этот браузер не поддерживает запись экрана.');
        return false;
    }
    if (screenCaptureReady && screenAgent && !screenAgent.closed) {
        if (screenPreviewStream) showScreenPreview();
        return true;
    }
    if (screenCaptureRequestPending && screenAgent && !screenAgent.closed) {
        if (fromUserGesture) { try { screenAgent.focus(); } catch (_) { } }
        return false;
    }

    clearCameraError();
    screenCaptureRequestPending = true;
    renderScreenPlaceholder();
    fitWindow();
    try {
        await openScreenCaptureAgent(fromUserGesture);
        reportDesktopEvent('SCREEN_AGENT_OPENED', JSON.stringify({
            systemAudio: !!captureSystemAudio.checked,
            microphone: !!captureMicrophone.checked
        }));
        return screenCaptureReady;
    } catch (error) {
        screenCaptureRequestPending = false;
        setCameraError(error?.message || 'Не удалось открыть выбор экрана.');
        renderScreenPlaceholder();
        return false;
    }
}

function initializeScreenAgentMessages() {
    window.__videoOfferAcceptScreenStream = (agentId, stream) => {
        if (!agentId || agentId !== screenAgentId || !stream?.getVideoTracks?.().length) return false;
        screenPreviewStream = stream;
        if (captureMode === 'SCREEN' && !recordedPreviewUrl) showScreenPreview();
        return true;
    };

    window.addEventListener('message', event => {
        if (event.origin !== location.origin) return;
        const message = event.data || {};
        if (message.channel !== SCREEN_AGENT_CHANNEL) return;
        if (message.agentId !== screenAgentId) return;
        if (screenAgent && event.source !== screenAgent) return;
        void handleScreenAgentMessage(message);
    });

    window.addEventListener('beforeunload', () => closeScreenCaptureAgent());
}

async function handleScreenAgentMessage(message) {
    switch (message.type) {
        case 'AGENT_READY':
            screenAgentReady = true;
            screenCaptureRequestPending = true;
            sendScreenAgentMessage('INIT', {
                contextToken,
                systemAudio: !!captureSystemAudio.checked,
                microphone: !!captureMicrophone.checked
            });
            break;
        case 'INITIALIZED':
            screenAgentReady = true;
            break;
        case 'CAPTURE_READY':
            screenCaptureReady = true;
            screenCaptureRequestPending = false;
            clearCameraError();
            resolveScreenReadyWaiters();
            recordToggleButton.hidden = false;
            if (captureMode === 'SCREEN') {
                if (screenPreviewStream) showScreenPreview();
                else renderScreenPlaceholder('Экран готов к записи.');
            }
            reportDesktopEvent('SCREEN_CAPTURE_READY', JSON.stringify(message.details || {}));
            break;
        case 'CAPTURE_ERROR': {
            screenCaptureReady = false;
            screenCaptureRequestPending = false;
            const error = new Error(message.message || 'Не удалось получить экран.');
            rejectScreenReadyWaiters(error);
            setCameraError(error.message);
            renderScreenPlaceholder();
            reportDesktopEvent('SCREEN_CAPTURE_ERROR', JSON.stringify({name: message.name || '', detail: message.message || ''}));
            break;
        }
        case 'PREVIEW_OFFER':
            await acceptScreenPreviewOffer(message.description);
            break;
        case 'PREVIEW_ICE':
            await acceptScreenPreviewIce(message.candidate);
            break;
        case 'SEGMENT_STARTED': {
            const index = Number(message.segmentIndex);
            const waiter = screenStartWaiters.get(index);
            if (waiter) {
                clearTimeout(waiter.timer);
                screenStartWaiters.delete(index);
                waiter.resolve();
            }
            break;
        }
        case 'SEGMENT_PROGRESS':
            if (!uploadProcessing.hidden && Number.isFinite(Number(message.percent))) {
                setRecordingProgress(Math.max(0, Math.min(99, Number(message.percent))));
            }
            break;
        case 'SEGMENT_READY': {
            const index = Number(message.segmentIndex);
            const waiter = screenStopWaiters.get(index);
            if (waiter) {
                clearTimeout(waiter.timer);
                screenStopWaiters.delete(index);
                waiter.resolve(message.upload);
            }
            break;
        }
        case 'SEGMENT_ERROR': {
            const index = Number(message.segmentIndex);
            const waiter = screenStopWaiters.get(index);
            if (waiter) {
                clearTimeout(waiter.timer);
                screenStopWaiters.delete(index);
                waiter.reject(new Error(message.message || 'Ошибка записи экрана.'));
            }
            break;
        }
        case 'REQUEST_STOP_RECORDING':
            if (recordingSessionActive && currentSegment?.mode === 'SCREEN') {
                setCameraError('Демонстрация экрана завершена.');
                await stopRecordingSession();
            }
            break;
        case 'CAPTURE_ENDED':
            screenCaptureReady = false;
            screenCaptureRequestPending = false;
            releaseScreenPreview();
            if (captureMode === 'SCREEN' && !recordingSessionActive) renderScreenPlaceholder();
            break;
        case 'CAPTURE_RELEASED':
            screenCaptureReady = false;
            screenCaptureRequestPending = false;
            releaseScreenPreview();
            break;
        case 'AGENT_CLOSED':
            if (!screenAgentClosing) {
                screenAgentReady = false;
                screenCaptureReady = false;
                screenCaptureRequestPending = false;
                releaseScreenPreview();
                rejectScreenReadyWaiters(new Error('Окно выбора экрана закрыто.'));
                rejectAllScreenSegmentWaiters(new Error('Окно записи экрана закрыто.'));
                if (recordingSessionActive && currentSegment?.mode === 'SCREEN') {
                    setCameraError('Запись экрана прервана.');
                    await stopRecordingSession();
                } else if (captureMode === 'SCREEN') {
                    renderScreenPlaceholder();
                }
            }
            break;
        case 'ACTIVATION_REQUIRED':
            reportDesktopEvent('SCREEN_AGENT_ACTIVATION_REQUIRED', JSON.stringify({
                reason: message.reason || '',
                userActivation: !!message.userActivation
            }));
            break;
        case 'WINDOW_GEOMETRY':
            reportDesktopEvent('SCREEN_AGENT_WINDOW_GEOMETRY', JSON.stringify({
                state: message.state || '',
                outerWidth: message.outerWidth || null,
                outerHeight: message.outerHeight || null,
                innerWidth: message.innerWidth || null,
                innerHeight: message.innerHeight || null,
                screenX: message.screenX ?? null,
                screenY: message.screenY ?? null
            }));
            break;
        case 'NATIVE_CAPABILITIES':
            reportDesktopEvent('SCREEN_AGENT_NATIVE_CAPABILITIES', JSON.stringify({
                windowMinimize: !!message.windowMinimize,
                windowSetResizable: !!message.windowSetResizable,
                windowManagementPermission: message.windowManagementPermission || 'unknown',
                chromeAppWindow: !!message.chromeAppWindow,
                chromeWindows: !!message.chromeWindows,
                electronRequire: !!message.electronRequire,
                electronAPI: !!message.electronAPI,
                desktopAPI: !!message.desktopAPI,
                nativeWindow: !!message.nativeWindow
            }));
            break;
        case 'REQUEST_HOST_FOCUS':
            focusBitrixHostWindow();
            break;
        case 'AGENT_PARKED':
            reportDesktopEvent('SCREEN_AGENT_PARKED', JSON.stringify({method: message.method || 'unknown'}));
            break;
        case 'AGENT_ERROR':
            setCameraError(message.message || 'Ошибка записи экрана.');
            break;
        default:
            break;
    }
    fitWindow();
}

function focusBitrixHostWindow() {
    // Run the focus handoff from the embedded app as well as from the capture
    // agent. Some Chromium shells refocus the popup after getDisplayMedia()
    // resolves, so repeat the request for a short period.
    try { window.blur(); } catch (_) { }
    try { window.parent?.focus(); } catch (_) { }
    try { window.top?.focus(); } catch (_) { }
    const delays = [40, 120, 260, 520, 900, 1500, 2200];
    delays.forEach(delay => setTimeout(() => {
        try { window.parent?.focus(); } catch (_) { }
        try { window.top?.focus(); } catch (_) { }
    }, delay));
}

async function openScreenCaptureAgent(focus = true) {
    if (screenAgent && !screenAgent.closed) {
        if (screenAgentReady) {
            sendScreenAgentMessage('INIT', {
                contextToken,
                systemAudio: !!captureSystemAudio.checked,
                microphone: !!captureMicrophone.checked
            });
        }
        if (focus) { try { screenAgent.focus(); } catch (_) { } }
        return screenAgent;
    }

    screenAgentClosing = false;
    screenAgentReady = false;
    screenCaptureReady = false;
    releaseScreenPreview();
    screenAgentId = createScreenAgentId();
    const url = `${SCREEN_AGENT_URL}&agentId=${encodeURIComponent(screenAgentId)}`;
    const availLeft = Number(screen.availLeft) || 0;
    const availTop = Number(screen.availTop) || 0;
    const availWidth = Number(screen.availWidth) || Number(screen.width) || SCREEN_AGENT_PICKER_OUTER_WIDTH;
    const availHeight = Number(screen.availHeight) || Number(screen.height) || SCREEN_AGENT_PICKER_OUTER_HEIGHT;
    const left = availLeft + Math.max(0, Math.round((availWidth - SCREEN_AGENT_PICKER_OUTER_WIDTH) / 2));
    const top = availTop + Math.max(0, Math.round((availHeight - SCREEN_AGENT_PICKER_OUTER_HEIGHT) / 2));
    const features = [
        'popup=yes',
        `width=${SCREEN_AGENT_INITIAL_INNER_WIDTH}`,
        `height=${SCREEN_AGENT_INITIAL_INNER_HEIGHT}`,
        `left=${left}`,
        `top=${top}`,
        'resizable=no',
        'scrollbars=no',
        'menubar=no',
        'toolbar=no',
        'location=no',
        'status=no'
    ].join(',');
    screenAgent = window.open(
        url,
        `videoOfferScreenCapture_${screenAgentId.replace(/[^a-zA-Z0-9]/g, '')}`,
        features);
    if (!screenAgent) {
        screenAgentId = null;
        throw new Error('Браузер заблокировал окно выбора экрана. Разрешите всплывающие окна и повторите.');
    }
    if (focus) { try { screenAgent.focus(); } catch (_) { } }
    return screenAgent;
}

function createScreenAgentId() {
    try { return crypto.randomUUID(); } catch (_) { return `${Date.now()}-${Math.random().toString(36).slice(2)}`; }
}

function sendScreenAgentMessage(type, payload = {}) {
    if (!screenAgent || screenAgent.closed || !screenAgentId) return false;
    try {
        screenAgent.postMessage({channel: SCREEN_AGENT_CHANNEL, agentId: screenAgentId, type, ...payload}, location.origin);
        return true;
    } catch (_) {
        return false;
    }
}

function waitForScreenCaptureReady(timeoutMs = 5 * 60 * 1000) {
    if (screenCaptureReady) return Promise.resolve();
    return new Promise((resolve, reject) => {
        const waiter = {resolve, reject, timer: null};
        waiter.timer = setTimeout(() => {
            screenReadyWaiters = screenReadyWaiters.filter(item => item !== waiter);
            reject(new Error('Экран не был выбран.'));
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

function rejectAllScreenSegmentWaiters(error) {
    for (const [index, waiter] of screenStartWaiters.entries()) {
        clearTimeout(waiter.timer);
        waiter.reject(error);
        screenStartWaiters.delete(index);
    }
    for (const [index, waiter] of screenStopWaiters.entries()) {
        clearTimeout(waiter.timer);
        waiter.reject(error);
        screenStopWaiters.delete(index);
    }
}

async function acceptScreenPreviewOffer(description) {
    if (!description || typeof RTCPeerConnection === 'undefined') return;
    const queuedCandidates = screenPreviewPendingCandidates.splice(0);
    releaseScreenPreviewPeerOnly();
    const peer = new RTCPeerConnection({iceServers: []});
    screenPreviewPeer = peer;
    peer.ontrack = event => {
        const stream = event.streams?.[0] || new MediaStream([event.track]);
        screenPreviewStream = stream;
        if (captureMode === 'SCREEN' && !recordedPreviewUrl) showScreenPreview();
    };
    peer.onicecandidate = event => {
        if (event.candidate) sendScreenAgentMessage('PREVIEW_ICE', {candidate: event.candidate.toJSON ? event.candidate.toJSON() : event.candidate});
    };
    peer.onconnectionstatechange = () => {
        if (peer !== screenPreviewPeer) return;
        if (['failed', 'closed'].includes(peer.connectionState) && !screenPreviewStream) {
            reportDesktopEvent('SCREEN_PREVIEW_WEBRTC_FAILED', peer.connectionState);
        }
    };
    try {
        await peer.setRemoteDescription(description);
        for (const candidate of [...queuedCandidates, ...screenPreviewPendingCandidates.splice(0)]) {
            try { await peer.addIceCandidate(candidate); } catch (_) { }
        }
        const answer = await peer.createAnswer();
        await peer.setLocalDescription(answer);
        sendScreenAgentMessage('PREVIEW_ANSWER', {description: {type: peer.localDescription.type, sdp: peer.localDescription.sdp}});
    } catch (error) {
        reportDesktopEvent('SCREEN_PREVIEW_WEBRTC_ERROR', error?.message || String(error));
        releaseScreenPreviewPeerOnly();
    }
}

async function acceptScreenPreviewIce(candidate) {
    if (!candidate) return;
    if (!screenPreviewPeer || !screenPreviewPeer.remoteDescription) {
        screenPreviewPendingCandidates.push(candidate);
        return;
    }
    try { await screenPreviewPeer.addIceCandidate(candidate); } catch (_) { }
}

function releaseScreenPreviewPeerOnly() {
    if (screenPreviewPeer) {
        try { screenPreviewPeer.ontrack = null; screenPreviewPeer.onicecandidate = null; screenPreviewPeer.close(); } catch (_) { }
    }
    screenPreviewPeer = null;
    screenPreviewPendingCandidates = [];
}

function releaseScreenPreview() {
    if (cameraPreview.srcObject === screenPreviewStream) cameraPreview.srcObject = null;
    screenPreviewStream = null;
    releaseScreenPreviewPeerOnly();
}

function closeScreenCaptureAgent() {
    const agent = screenAgent;
    const id = screenAgentId;
    screenAgentClosing = true;
    screenAgentReady = false;
    screenCaptureReady = false;
    screenCaptureRequestPending = false;
    releaseScreenPreview();
    rejectScreenReadyWaiters(new Error('Выбор экрана отменён.'));
    rejectAllScreenSegmentWaiters(new Error('Запись экрана завершена.'));
    if (agent && !agent.closed) {
        try { agent.postMessage({channel: SCREEN_AGENT_CHANNEL, agentId: id, type: 'SHUTDOWN'}, location.origin); } catch (_) { }
        try { agent.close(); } catch (_) { }
    }
    screenAgent = null;
    screenAgentId = null;
    setTimeout(() => { screenAgentClosing = false; }, 0);
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
        try { videoTrack.contentHint = 'motion'; } catch (_) { }
        cameraPreview.hidden = false;
        cameraPlaceholder.hidden = true;
        cameraPreview.controls = false;
        cameraPreview.muted = true;
        cameraPreview.autoplay = true;
        cameraPreview.srcObject = cameraStream;
        recordFullscreenButton.hidden = true;
        await cameraPreview.play().catch(async () => {
            await new Promise(resolve => requestAnimationFrame(resolve));
            if (cameraPreview.srcObject === cameraStream) await cameraPreview.play().catch(() => {});
        });
        await refreshCameraDevices();
        const id = videoTrack.getSettings?.().deviceId;
        const index = cameraDevices.findIndex(device => device.deviceId === id);
        if (index >= 0) activeCameraIndex = index;
        reportDesktopEvent('CAMERA_READY', JSON.stringify({settings: videoTrack.getSettings?.() || {}, devices: cameraDevices.length}));
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
    const stream = cameraStream;
    if (stream) {
        try { stream.getTracks().forEach(track => track.stop()); } catch (_) { }
        const sources = stream._sourceStreams || [];
        sources.forEach(source => { try { source.getTracks().forEach(track => track.stop()); } catch (_) { } });
    }
    cameraStream = null;
    if (stream && cameraPreview.srcObject === stream) cameraPreview.srcObject = null;
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
                await ensureScreenCapture(true);
                if (!screenCaptureReady) return;
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
                await ensureScreenCapture(true);
                if (!screenCaptureReady) await waitForScreenCaptureReady();
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
            showScreenPreview();
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
    uploadStatus.textContent = 'Загрузка видео';
    uploadProgressText.textContent = '0%';
    uploadProgressBar.style.width = '0%';
    stopSessionTimer(false);
    updateRecordButtonState(false);

    try {
        const current = currentSegment;
        if (current.mode === 'CAMERA') segmentPromises[current.index] = stopCameraSegment();
        else segmentPromises[current.index] = stopScreenSegment(current.index);
        currentSegment = null;

        const results = await Promise.all(segmentPromises.filter(Boolean));
        if (!results.length) throw new Error('Запись не содержит видеоданных.');
        // The capture agent is no longer needed once every screen segment is safely READY.
        // Close it before merge/preview work so the technical window disappears as early as possible.
        closeScreenCaptureAgent();
        setRecordingProgress(results.length > 1 ? 98 : 100);
        finalUploadSession = results.length === 1 ? results[0] : await mergeRecordingSegments(results);
        setRecordingProgress(100);
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
        closeScreenCaptureAgent();
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
        ? new MediaRecorder(cameraStream, {mimeType, videoBitsPerSecond: 3500000, audioBitsPerSecond: 128000})
        : new MediaRecorder(cameraStream, {videoBitsPerSecond: 3500000, audioBitsPerSecond: 128000});
    const uploader = new ChunkUploader(contextToken, recorder.mimeType || mimeType || 'video/webm', 'RECORDING', null, renderRecordingUploadProgress);
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
    reportDesktopEvent('CAMERA_RECORDING_STARTED', JSON.stringify({mimeType: recorder.mimeType || mimeType || 'default', videoBitsPerSecond: recorder.videoBitsPerSecond || null, audioBitsPerSecond: recorder.audioBitsPerSecond || null}));
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
    cameraPreview.controls = true;
    cameraPreview.preload = 'auto';
    cameraPreview.muted = false;
    cameraPreview.srcObject = null;
    cameraPreview.src = recordedPreviewUrl;
    cameraPreview.load();
    prepareVideoFirstFrame(cameraPreview);
    playRecordingButton.hidden = true;
    recordFullscreenButton.hidden = false;
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
    // Never pause a live camera/screen srcObject. 023 did this and froze the preview
    // exactly when recording started.
    if (cameraPreview.srcObject) {
        cameraPreview.controls = false;
        cameraPreview.muted = true;
        cameraPreview.autoplay = true;
        if (cameraPreview.paused) cameraPreview.play().catch(() => {});
    } else {
        try { cameraPreview.pause(); } catch (_) { }
        cameraPreview.removeAttribute('src');
        try { cameraPreview.load(); } catch (_) { }
    }
    playRecordingButton.hidden = true;
    recordFullscreenButton.hidden = true;
    exitLocalVideoFullscreen(cameraPreview, recordFullscreenButton);
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

function startScreenSegment(index) {
    if (!screenCaptureReady || !screenAgent || screenAgent.closed) {
        return Promise.reject(new Error('Экран не выбран.'));
    }
    return new Promise((resolve, reject) => {
        const timer = setTimeout(() => {
            screenStartWaiters.delete(index);
            reject(new Error('Запись экрана не запустилась.'));
        }, 20000);
        screenStartWaiters.set(index, {resolve, reject, timer});
        if (!sendScreenAgentMessage('START_SEGMENT', {segmentIndex: index})) {
            clearTimeout(timer);
            screenStartWaiters.delete(index);
            reject(new Error('Окно записи экрана закрыто.'));
        }
    });
}

function stopScreenSegment(index) {
    if (!screenAgent || screenAgent.closed) {
        return Promise.reject(new Error('Окно записи экрана закрыто.'));
    }
    return new Promise((resolve, reject) => {
        const timer = setTimeout(() => {
            screenStopWaiters.delete(index);
            reject(new Error('Запись экрана не завершила сохранение.'));
        }, 20 * 60 * 1000);
        screenStopWaiters.set(index, {resolve, reject, timer});
        if (!sendScreenAgentMessage('STOP_SEGMENT', {segmentIndex: index})) {
            clearTimeout(timer);
            screenStopWaiters.delete(index);
            reject(new Error('Окно записи экрана закрыто.'));
        }
    });
}

function initializeFileUpload() {
    chooseFileButton.addEventListener('click', () => { fileInput.value = ''; fileInput.click(); });
    replaceFileButton.addEventListener('click', () => { fileInput.value = ''; fileInput.click(); });
    fileInput.addEventListener('change', async () => {
        const file = fileInput.files?.[0];
        if (!file) return;
        await handleSelectedFile(file);
    });
}

async function handleSelectedFile(file) {
    const generation = ++fileUploadGeneration;
    setError(null);
    clearFilePreview();
    if (activeFileUploader) {
        const previousUploader = activeFileUploader;
        activeFileUploader = null;
        previousUploader.discard().catch(() => {});
    }
    try {
        validateVideoFile(file);
        await discardFinalUpload();
        if (generation !== fileUploadGeneration) return;
        selectedFileCard.hidden = false;
        selectedFileName.textContent = file.name;
        selectedFileMeta.textContent = `${formatBytes(file.size)} · ${file.type || fileExtension(file.name).toUpperCase()}`;
        chooseFileButton.hidden = true;
        offerFields.hidden = true;
        submitButton.hidden = true;
        fileUploadProcessing.hidden = false;
        setFileProgress(0, 'Загрузка видео');

        const uploader = new ChunkUploader(
            contextToken,
            file.type || mimeFromFileName(file.name),
            'FILE',
            file.size,
            progress => {
                if (generation === fileUploadGeneration) renderFileUploadProgress(progress, file.size);
            });
        activeFileUploader = uploader;
        await uploader.init();
        if (generation !== fileUploadGeneration) {
            await uploader.discard().catch(() => {});
            return;
        }
        const ready = await uploader.uploadFile(file, FILE_CHUNK_BYTES);
        if (generation !== fileUploadGeneration) {
            await uploader.discard().catch(() => {});
            return;
        }
        finalUploadSession = ready;
        activeFileUploader = null;
        setFileProgress(100, 'Загрузка видео');
        fileUploadProcessing.hidden = true;
        showFilePreview(finalUploadSession);
        offerFields.hidden = false;
        submitButton.hidden = false;
    } catch (error) {
        if (generation !== fileUploadGeneration || error?.cancelledUpload) return;
        if (activeFileUploader) {
            const failedUploader = activeFileUploader;
            activeFileUploader = null;
            failedUploader.discard().catch(() => {});
        }
        fileUploadProcessing.hidden = true;
        chooseFileButton.hidden = false;
        setError(error.message || 'Не удалось загрузить видеофайл');
    }
    fitWindow();
}

function renderFileUploadProgress(progress, fileSize) {
    const percent = Math.max(0, Math.min(100, Number(progress?.percent) || 0));
    setFileProgress(percent, 'Загрузка видео');
}

function initializeFullscreenControls() {
    fileFullscreenButton?.addEventListener('click', () => toggleVideoFullscreen(filePreview, fileFullscreenButton));
    recordFullscreenButton?.addEventListener('click', () => toggleVideoFullscreen(cameraPreview, recordFullscreenButton));
    document.addEventListener('fullscreenchange', syncFullscreenButtons);
    document.addEventListener('webkitfullscreenchange', syncFullscreenButtons);
    document.addEventListener('keydown', event => {
        if (event.key !== 'Escape') return;
        exitLocalVideoFullscreen(filePreview, fileFullscreenButton);
        exitLocalVideoFullscreen(cameraPreview, recordFullscreenButton);
    });
    filePreview?.addEventListener('dblclick', () => toggleVideoFullscreen(filePreview, fileFullscreenButton));
    cameraPreview?.addEventListener('dblclick', () => {
        if (recordedPreviewUrl && !cameraPreview.srcObject) toggleVideoFullscreen(cameraPreview, recordFullscreenButton);
    });
}

async function toggleVideoFullscreen(video, button) {
    if (!video) return;
    if (video.classList.contains('is-local-fullscreen')) {
        exitLocalVideoFullscreen(video, button);
        return;
    }
    const current = document.fullscreenElement || document.webkitFullscreenElement;
    if (current) {
        try {
            if (document.exitFullscreen) await document.exitFullscreen();
            else if (document.webkitExitFullscreen) document.webkitExitFullscreen();
        } catch (_) { }
        return;
    }
    try {
        if (video.requestFullscreen) {
            await video.requestFullscreen();
            syncFullscreenButtons();
            return;
        }
        if (video.webkitRequestFullscreen) {
            video.webkitRequestFullscreen();
            return;
        }
        if (video.webkitEnterFullscreen) {
            video.webkitEnterFullscreen();
            return;
        }
    } catch (error) {
        console.warn('[video-offer] native fullscreen rejected; using iframe-local fullscreen', error?.name, error?.message);
    }
    // Bitrix may not delegate the browser fullscreen permission to a placement iframe.
    // In that case fill the entire available application iframe instead of doing nothing.
    video.classList.add('is-local-fullscreen');
    document.body.classList.add('has-local-video-fullscreen');
    if (button) {
        button.classList.add('is-local-exit');
        button.textContent = '×';
        button.title = 'Закрыть полноэкранный режим';
        button.setAttribute('aria-label', 'Закрыть полноэкранный режим');
    }
}

function exitLocalVideoFullscreen(video, button) {
    if (!video) return;
    video.classList.remove('is-local-fullscreen');
    if (!document.querySelector('video.is-local-fullscreen')) document.body.classList.remove('has-local-video-fullscreen');
    if (button) {
        button.classList.remove('is-local-exit');
        button.textContent = '⛶';
        button.title = 'Развернуть видео';
        button.setAttribute('aria-label', 'Развернуть видео');
    }
}

function syncFullscreenButtons() {
    const active = document.fullscreenElement || document.webkitFullscreenElement;
    if (!active) {
        for (const button of [fileFullscreenButton, recordFullscreenButton]) {
            if (!button || button.classList.contains('is-local-exit')) continue;
            button.textContent = '⛶';
            button.title = 'Развернуть видео';
            button.setAttribute('aria-label', 'Развернуть видео');
        }
    }
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
    filePreviewFrame.hidden = false;
    filePreview.hidden = false;
    filePreview.src = data.previewUrl || '';
    filePreview.preload = 'auto';
    filePreview.load();
    prepareVideoFirstFrame(filePreview);
}
function clearFilePreview() {
    filePreview.pause?.();
    filePreview.removeAttribute('src');
    filePreview.hidden = true;
    filePreviewFrame.hidden = true;
    exitLocalVideoFullscreen(filePreview, fileFullscreenButton);
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
        this.completedBytes = 0;
        this.enqueuedBytes = 0;
        this.inFlightBytes = new Map();
        this.totalExpected = 0;
        this.waiters = [];
        this.generation = 1;
        this.activeRequests = new Set();
    }
    async init() {
        this.emitProgress('starting', 0);
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
        this.enqueuedBytes += blob.size;
        this.queue.push({sequence, blob, generation: this.generation});
        this.emitProgress('uploading');
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
                    this.completed++;
                    this.completedBytes += task.blob.size;
                    this.emitProgress('uploading');
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
    progressSnapshot(phase, forcedPercent) {
        const activeBytes = [...this.inFlightBytes.values()].reduce((sum, value) => sum + Math.max(0, Number(value) || 0), 0);
        const loadedBytes = this.completedBytes + activeBytes;
        const totalBytes = Math.max(Number(this.declaredSizeBytes) || 0, this.enqueuedBytes, 1);
        const percent = forcedPercent == null
            ? Math.max(0, Math.min(65, Math.round(loadedBytes * 65 / totalBytes)))
            : Math.max(0, Math.min(100, Math.round(forcedPercent)));
        return {phase, percent, loadedBytes: Math.min(loadedBytes, totalBytes), totalBytes};
    }
    emitProgress(phase, forcedPercent) {
        try { this.progressCallback(this.progressSnapshot(phase, forcedPercent)); } catch (_) { }
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
            this.inFlightBytes.set(sequence, 0);
            try {
                return await this.uploadChunkAttempt(blob, sequence);
            } catch (error) {
                this.inFlightBytes.set(sequence, 0);
                this.emitProgress('uploading');
                lastError = error;
                if (attempt < 4) await sleep(Math.min(1600, attempt * 400));
            }
        }
        throw lastError || new Error('Не удалось загрузить часть видео');
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
                const loaded = Math.min(blob.size, Math.max(0, Number(event.loaded) || 0));
                this.inFlightBytes.set(sequence, loaded);
                this.emitProgress('uploading');
            };
            xhr.onload = () => {
                this.activeRequests.delete(xhr);
                const data = parseJsonText(xhr.responseText);
                if (xhr.status >= 200 && xhr.status < 300) resolve(data);
                else reject(new Error(data.message || 'Сервер не принял часть видео'));
            };
            xhr.onerror = () => { this.activeRequests.delete(xhr); reject(new Error('Ошибка сети при загрузке видео')); };
            xhr.ontimeout = () => { this.activeRequests.delete(xhr); reject(new Error('Сервер слишком долго принимал видео')); };
            xhr.onabort = () => { this.activeRequests.delete(xhr); reject(new Error('Загрузка видео отменена')); };
            xhr.send(blob);
        });
    }
    async finish(timeoutMs = UPLOAD_DRAIN_TIMEOUT_MS) {
        this.totalExpected = this.nextSequence;
        await this.waitDrain(timeoutMs);
        if (this.failure) throw this.failure;
        if (this.totalExpected <= 0) throw new Error('Видео не содержит данных.');
        this.emitProgress('uploaded', 65);
        const response = await fetchWithTimeout(
            `/bitrix/mobile/uploads/${encodeURIComponent(this.session.id)}/complete?chunkCount=${encodeURIComponent(this.totalExpected)}`,
            {method: 'POST', headers: {'X-Upload-Token': this.session.uploadToken}}, 60000);
        const data = await readJson(response);
        if (!response.ok) throw new Error(data.message || 'Не удалось завершить загрузку видео');
        this.session = data;
        this.emitProgress('processing', 66);
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
            if (data.status === 'READY') {
                this.emitProgress('ready', this.sourceKind === 'RECORDING' ? 99 : 100);
                return data;
            }
            if (data.status === 'ERROR') throw new Error(data.errorMessage || 'Не удалось обработать видео');
            const serverProgress = Math.max(0, Math.min(99, Number(data.processingProgressPercent) || 0));
            this.emitProgress('processing', Math.max(66, Math.min(99, 66 + Math.floor(serverProgress * 33 / 100))));
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
        return this.finish(Math.max(UPLOAD_DRAIN_TIMEOUT_MS, 10 * 60 * 1000));
    }
    cancel() {
        this.generation++;
        const error = new Error('Загрузка заменена');
        error.cancelledUpload = true;
        this.failure = error;
        this.queue = [];
        this.inFlightBytes.clear();
        this.activeRequests.forEach(xhr => { try { xhr.abort(); } catch (_) { } });
        this.activeRequests.clear();
        this.notify();
    }
    async discard() {
        const session = this.session;
        this.cancel();
        if (!session?.id || !session?.uploadToken || session.status === 'CONSUMED') return;
        try {
            await fetch(`/bitrix/mobile/uploads/${encodeURIComponent(session.id)}`, {
                method: 'DELETE', headers: {'X-Upload-Token': session.uploadToken}
            });
        } catch (_) { }
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
    fileUploadGeneration++;
    if (activeFileUploader) {
        const uploader = activeFileUploader;
        activeFileUploader = null;
        uploader.discard().catch(() => {});
    }
    stopCameraCapture();
    closeScreenCaptureAgent();
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


function renderRecordingUploadProgress(progress) {
    if (uploadProcessing.hidden) return;
    const percent = Math.max(0, Math.min(99, Number(progress?.percent) || 0));
    setRecordingProgress(percent);
}

function setRecordingProgress(percent) {
    const normalized = Math.max(0, Math.min(100, Math.round(Number(percent) || 0)));
    uploadStatus.textContent = 'Загрузка видео';
    uploadProgressText.textContent = normalized + '%';
    uploadProgressBar.style.width = normalized + '%';
}

function prepareVideoFirstFrame(video) {
    if (!video) return;
    const reveal = () => {
        try {
            video.pause();
            const duration = Number(video.duration);
            if (Number.isFinite(duration) && duration > 0.05) video.currentTime = Math.min(0.05, duration / 10);
        } catch (_) { }
    };
    if (video.readyState >= 2) reveal();
    else video.addEventListener('loadeddata', reveal, {once: true});
}

function featureAllowed(name) {
    const policy = document.permissionsPolicy || document.featurePolicy;
    if (!policy || typeof policy.allowsFeature !== 'function') return null;
    try { return !!policy.allowsFeature(name); } catch (_) { return null; }
}

function reportDesktopEvent(event, details) {
    try {
        fetch('/bitrix/client-events', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({contextToken, event, details: details || ''}),
            keepalive: true
        }).catch(() => {});
    } catch (_) { }
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
    const percent = Math.max(0, Math.min(100, Number(data.progressPercent) || 0));
    if (data.status === 'ERROR') {
        processing.hidden = true;
        setError(data.errorMessage || 'Не удалось подготовить видеооффер');
        fitWindow();
        return;
    }
    if (data.status === 'CANCELLED') {
        processing.hidden = true;
        setError('Подготовка видео отменена');
        fitWindow();
        return;
    }
    if (data.status !== 'READY') {
        readyResult.hidden = true;
        deliveryStatus.textContent = '';
        renderProgress(percent, 'Загрузка видео');
    } else if (data.bitrixDeliveryStatus === 'DELIVERED' || data.bitrixDeliveryStatus === 'NOT_REQUIRED') {
        processing.hidden = true;
        readyResult.hidden = false;
        deliveryStatus.textContent = '';
        readyMessage.textContent = 'Ссылка добавлена в таймлайн. ' + viewGoalMessage(data.viewNotificationGoal);
    } else if (data.bitrixDeliveryStatus === 'ERROR') {
        processing.hidden = true;
        readyResult.hidden = false;
        deliveryStatus.textContent = '';
        readyMessage.textContent = data.bitrixDeliveryError
            ? 'Видео готово, но ссылку не удалось добавить в карточку: ' + data.bitrixDeliveryError
            : 'Видео готово, но ссылку не удалось добавить в карточку Bitrix24.';
    } else {
        readyResult.hidden = true;
        deliveryStatus.textContent = '';
        renderProgress(100, 'Загрузка видео');
    }
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
function parseJsonText(text) { if (!text) return {}; try { return JSON.parse(text); } catch (_) { return {message: text}; } }
function sleep(ms) { return new Promise(resolve => setTimeout(resolve, ms)); }

function initializeBitrixFrame() {
    if (typeof BX24 === 'undefined') return;
    BX24.init(function () { BX24.resizeWindow(860, 860); BX24.fitWindow(); });
}
function fitWindow() { if (typeof BX24 === 'undefined') return; try { BX24.fitWindow(); } catch (_) { } }
function viewGoalMessage(goal) {
    return ({ONE_MINUTE:'Уведомим, когда клиент посмотрит одну минуту видео.',HALF:'Уведомим, когда клиент досмотрит видео до середины.',COMPLETED:'Уведомим, когда клиент досмотрит видео целиком.',NONE:'Уведомление о просмотре отключено.'})[goal] || '';
}
