const token = location.pathname.split('/').filter(Boolean).pop();
const video = document.getElementById('video');
const text = document.getElementById('text');
const state = document.getElementById('state');
const placeholder = document.getElementById('video-placeholder');
const placeholderTitle = document.getElementById('placeholder-title');
const placeholderText = document.getElementById('placeholder-text');
const previewMode = new URLSearchParams(location.search).get('preview') === '1';

let startedSent = false;
let completedSent = false;
let pageOpenedSent = false;
let pollingTimer = null;

let viewTrackingActive = false;
let viewSessionId = createSessionId();
let watchedSeconds = 0;
let lastSampleWallTime = null;
let lastSampleMediaTime = null;
let lastProgressSentAt = 0;
let playbackSampler = null;
let goalReached = false;

async function loadOffer() {
    try {
        const response = await fetch('/api/public/offers/' + token, {cache: 'no-store'});
        const data = await response.json();
        if (!response.ok) throw new Error(data.message || 'Предложение не найдено');

        text.textContent = data.text || '';
        text.hidden = !data.text;
        viewTrackingActive = !previewMode && Boolean(data.viewTrackingActive) && !goalReached;

        if (!pageOpenedSent) {
            pageOpenedSent = true;
            sendEvent('PAGE_OPENED', 0);
        }

        if (data.ready) {
            clearInterval(pollingTimer);
            placeholder.hidden = true;
            video.hidden = false;
            if (!video.src) video.src = '/media/' + token;
            state.innerHTML = previewMode
                ? '<span></span> Режим предпросмотра — просмотр не учитывается'
                : '<span></span> Видео готово к просмотру';
        } else if (data.status === 'ERROR') {
            clearInterval(pollingTimer);
            placeholderTitle.textContent = 'Видео временно недоступно';
            placeholderText.textContent = 'Пожалуйста, свяжитесь с менеджером';
            state.textContent = 'Не удалось подготовить видеопрезентацию';
        } else {
            const progress = data.progressPercent || 0;
            placeholderTitle.textContent = 'Подготавливаем видео — ' + progress + '%';
            state.textContent = 'Видео ещё подготавливается';
            if (!pollingTimer) pollingTimer = setInterval(loadOffer, 2000);
        }
    } catch (error) {
        clearInterval(pollingTimer);
        placeholderTitle.textContent = 'Не удалось открыть презентацию';
        placeholderText.textContent = error.message;
        state.textContent = 'Ошибка загрузки страницы';
    }
}

video.addEventListener('play', () => {
    if (!startedSent) {
        startedSent = true;
        sendEvent('VIDEO_STARTED', video.currentTime);
    }
    resetPlaybackSample();
    startPlaybackSampler();
});

video.addEventListener('pause', () => {
    samplePlayback();
    stopPlaybackSampler();
    sendViewProgress('PAUSE');
});

video.addEventListener('seeking', resetPlaybackSample);
video.addEventListener('seeked', resetPlaybackSample);
video.addEventListener('ratechange', resetPlaybackSample);
video.addEventListener('waiting', resetPlaybackSample);

video.addEventListener('ended', () => {
    samplePlayback();
    stopPlaybackSampler();
    sendViewProgress('ENDED', true);
    if (!completedSent) {
        completedSent = true;
        sendEvent('VIDEO_COMPLETED', video.duration || video.currentTime);
    }
});

document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'hidden') {
        samplePlayback();
        sendViewProgress('HIDDEN', true);
    } else if (!video.paused && !video.ended) {
        resetPlaybackSample();
        startPlaybackSampler();
    }
});

window.addEventListener('pagehide', () => {
    samplePlayback();
    sendViewProgressWithBeacon('HIDDEN');
});

function startPlaybackSampler() {
    if (playbackSampler || !viewTrackingActive) return;
    playbackSampler = setInterval(() => {
        samplePlayback();
        if (Date.now() - lastProgressSentAt >= 5000) {
            sendViewProgress('HEARTBEAT');
        }
    }, 1000);
}

function stopPlaybackSampler() {
    if (!playbackSampler) return;
    clearInterval(playbackSampler);
    playbackSampler = null;
    resetPlaybackSample();
}

function samplePlayback() {
    if (!viewTrackingActive || video.paused || video.seeking || video.ended) {
        resetPlaybackSample();
        return;
    }

    const now = performance.now();
    const mediaTime = Number(video.currentTime) || 0;
    if (lastSampleWallTime !== null && lastSampleMediaTime !== null) {
        const wallDelta = Math.max(0, (now - lastSampleWallTime) / 1000);
        const mediaDelta = mediaTime - lastSampleMediaTime;
        const allowedMediaDelta = wallDelta * Math.max(1, Number(video.playbackRate) || 1) + 1.25;

        if (mediaDelta >= 0 && mediaDelta <= allowedMediaDelta) {
            watchedSeconds += mediaDelta;
        }
    }

    lastSampleWallTime = now;
    lastSampleMediaTime = mediaTime;
}

function resetPlaybackSample() {
    lastSampleWallTime = null;
    lastSampleMediaTime = null;
}

async function sendViewProgress(eventType, force = false) {
    if (!viewTrackingActive || goalReached) return;
    if (!force && Date.now() - lastProgressSentAt < 4000) return;

    lastProgressSentAt = Date.now();
    try {
        const response = await fetch('/api/public/offers/' + token + '/view-progress', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify(progressPayload(eventType)),
            keepalive: true
        });
        if (!response.ok) return;
        const data = await response.json();
        if (data.goalReached || !data.trackingActive) {
            goalReached = Boolean(data.goalReached);
            viewTrackingActive = false;
            stopPlaybackSampler();
        }
    } catch (_) {
        // Ошибка телеметрии не должна мешать просмотру видео.
    }
}

function sendViewProgressWithBeacon(eventType) {
    if (!viewTrackingActive || goalReached || !navigator.sendBeacon) return;
    const blob = new Blob(
        [JSON.stringify(progressPayload(eventType))],
        {type: 'application/json'}
    );
    navigator.sendBeacon('/api/public/offers/' + token + '/view-progress', blob);
}

function progressPayload(eventType) {
    return {
        sessionId: viewSessionId,
        positionSeconds: roundSeconds(video.currentTime),
        durationSeconds: Number.isFinite(video.duration) ? roundSeconds(video.duration) : null,
        watchedSeconds: roundSeconds(watchedSeconds),
        eventType
    };
}

function roundSeconds(value) {
    const number = Number(value) || 0;
    return Math.round(Math.max(0, number) * 1000) / 1000;
}

function createSessionId() {
    if (globalThis.crypto && typeof globalThis.crypto.randomUUID === 'function') {
        return globalThis.crypto.randomUUID();
    }
    return Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 14);
}

function sendEvent(eventType, playbackPositionSeconds) {
    const payload = JSON.stringify({eventType, playbackPositionSeconds});
    fetch('/api/public/offers/' + token + '/events', {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: payload,
        keepalive: true
    }).catch(() => {});
}

video.hidden = true;
loadOffer();
