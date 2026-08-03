const token = location.pathname.split('/').filter(Boolean).pop();
const video = document.getElementById('video');
const text = document.getElementById('text');
const state = document.getElementById('state');
const placeholder = document.getElementById('video-placeholder');
const placeholderTitle = document.getElementById('placeholder-title');
const placeholderText = document.getElementById('placeholder-text');

let startedSent = false;
let completedSent = false;
let pageOpenedSent = false;
let pollingTimer = null;

async function loadOffer() {
    try {
        const response = await fetch('/api/public/offers/' + token, {cache: 'no-store'});
        const data = await response.json();
        if (!response.ok) throw new Error(data.message || 'Предложение не найдено');

        text.textContent = data.text || '';
        text.hidden = !data.text;
        if (!pageOpenedSent) {
            pageOpenedSent = true;
            sendEvent('PAGE_OPENED', 0);
        }

        if (data.ready) {
            clearInterval(pollingTimer);
            placeholder.hidden = true;
            video.hidden = false;
            if (!video.src) video.src = '/media/' + token;
            state.innerHTML = '<span></span> Видео готово к просмотру';
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
    if (startedSent) return;
    startedSent = true;
    sendEvent('VIDEO_STARTED', video.currentTime);
});

video.addEventListener('ended', () => {
    if (completedSent) return;
    completedSent = true;
    sendEvent('VIDEO_COMPLETED', video.duration || video.currentTime);
});

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
