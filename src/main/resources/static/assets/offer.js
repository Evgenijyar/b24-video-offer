const token = location.pathname.split('/').filter(Boolean).pop();
const pageHost = document.getElementById('offer-page');
const previewMode = new URLSearchParams(location.search).get('preview') === '1';
const FONT_STACKS = {
    ARIAL: 'Arial, sans-serif',
    VERDANA: 'Verdana, sans-serif',
    GEORGIA: 'Georgia, serif',
    TIMES_NEW_ROMAN: '"Times New Roman", Times, serif',
    TREBUCHET_MS: '"Trebuchet MS", sans-serif',
    COURIER_NEW: '"Courier New", monospace'
};

let video = null;
let state = null;
let placeholder = null;
let placeholderTitle = null;
let placeholderText = null;
let pageRendered = false;
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

        if (!pageRendered) renderPage(data);
        viewTrackingActive = !previewMode && Boolean(data.viewTrackingActive) && !goalReached;

        if (!pageOpenedSent) {
            pageOpenedSent = true;
            sendEvent('PAGE_OPENED', 0);
        }

        if (data.ready) {
            clearInterval(pollingTimer);
            if (placeholder) placeholder.hidden = true;
            if (video) {
                video.hidden = false;
                if (!video.src) video.src = '/media/' + token;
            }
            if (state) state.innerHTML = previewMode
                ? '<span></span> Режим предпросмотра — просмотр не учитывается'
                : '<span></span> Видео готово к просмотру';
        } else if (data.status === 'ERROR') {
            clearInterval(pollingTimer);
            if (placeholderTitle) placeholderTitle.textContent = 'Видео временно недоступно';
            if (placeholderText) placeholderText.textContent = 'Пожалуйста, свяжитесь с менеджером';
            if (state) state.textContent = 'Не удалось подготовить видеопрезентацию';
        } else {
            const progress = data.progressPercent || 0;
            if (placeholderTitle) placeholderTitle.textContent = 'Подготавливаем видео — ' + progress + '%';
            if (state) state.textContent = 'Видео ещё подготавливается';
            if (!pollingTimer) pollingTimer = setInterval(loadOffer, 2000);
        }
    } catch (error) {
        clearInterval(pollingTimer);
        if (!pageRendered) {
            pageHost.innerHTML = `<div class="public-page-error"><h1>Не удалось открыть презентацию</h1><p>${escapeHtml(error.message || 'Ошибка загрузки')}</p></div>`;
        } else {
            if (placeholderTitle) placeholderTitle.textContent = 'Не удалось открыть презентацию';
            if (placeholderText) placeholderText.textContent = error.message || 'Ошибка загрузки';
            if (state) state.textContent = 'Ошибка загрузки страницы';
        }
    }
}

function renderPage(data) {
    const template = data.pageTemplate || {blocks: []};
    const content = data.pageContent || {text:{}, files:{}};
    const blocks = Array.isArray(template.blocks) ? template.blocks : [];
    pageHost.innerHTML = blocks.map(block => renderBlock(block, data, content)).join('');
    if (!pageHost.innerHTML.trim()) pageHost.innerHTML = '<div class="public-page-error"><h1>Страница не настроена</h1></div>';
    video = document.getElementById('video');
    state = document.getElementById('state');
    placeholder = document.getElementById('video-placeholder');
    placeholderTitle = document.getElementById('placeholder-title');
    placeholderText = document.getElementById('placeholder-text');
    if (video) {
        video.hidden = true;
        bindMainVideo();
    }
    pageRendered = true;
}

function renderBlock(block, data, content) {
    const c = block.config || {};
    const visibility = block.visibility === 'DESKTOP' ? 'vo-public-desktop-only' : block.visibility === 'MOBILE' ? 'vo-public-mobile-only' : '';
    const wrap = inner => `<section class="vo-public-block ${visibility}" data-block-type="${escapeHtml(block.type || '')}">${inner}</section>`;
    switch (block.type) {
        case 'HEADER': {
            const logo = c.logoUrl ? `<img src="${safeUrl(c.logoUrl)}" alt="${escapeHtml(c.logoName || 'Логотип')}">` : '';
            const phone = c.phoneText ? (c.phoneHref ? `<a href="${safeUrl(c.phoneHref)}"><span class="vo-phone-icon">☎</span>${escapeHtml(c.phoneText)}</a>` : `<span><span class="vo-phone-icon">☎</span>${escapeHtml(c.phoneText)}</span>`) : '';
            return wrap(`<header class="vo-public-header"><div class="vo-public-header-logo">${logo}</div><div class="vo-public-header-company">${escapeHtml(c.companyName || '')}</div><div class="vo-public-header-phone">${phone}</div></header>`);
        }
        case 'VIDEO': {
            if (c.source === 'MAIN') {
                return wrap(`${c.title ? `<h2 class="vo-public-section-title">${escapeHtml(c.title)}</h2>` : ''}<div class="video-frame vo-public-video-frame"><video id="video" controls playsinline preload="metadata"></video><div id="video-placeholder" class="video-placeholder"><div class="loader"></div><strong id="placeholder-title">Подготавливаем видео</strong><span id="placeholder-text">Страница обновится автоматически</span></div></div><div id="state" class="public-status"><span></span> Загружаем данные…</div>`);
            }
            if (!c.assetUrl) return '';
            return wrap(`${c.title ? `<h2 class="vo-public-section-title">${escapeHtml(c.title)}</h2>` : ''}<div class="video-frame vo-public-video-frame"><video controls playsinline preload="metadata" src="${safeUrl(c.assetUrl)}"></video></div>`);
        }
        case 'TEXT': {
            const value = c.mode === 'MANAGER'
                ? ((content.text || {})[block.id] || (c.fieldKey === 'accompanyingText' ? data.text : '') || '')
                : (c.text || '');
            if (!value) return '';
            const textStyle = textInlineStyle(c);
            if (c.style === 'HEADING') return wrap(`<h1 class="vo-public-heading" style="${escapeHtml(textStyle)}">${escapeHtml(value)}</h1>`);
            if (c.style === 'NOTE') return wrap(`<div class="vo-public-note" style="${escapeHtml(textStyle)}">${formatMultiline(value)}</div>`);
            return wrap(`<div class="vo-public-text" style="${escapeHtml(textStyle)}">${formatMultiline(value)}</div>`);
        }
        case 'IMAGE': {
            if (!c.assetUrl) return '';
            const image = `<img class="vo-public-image radius-${String(c.radius || 'LARGE').toLowerCase()}" style="${escapeHtml(imageContentStyle(c))}" src="${safeUrl(c.assetUrl)}" alt="${escapeHtml(c.alt || '')}">`;
            const content = c.href ? `<a class="vo-public-image-link" href="${safeUrl(c.href)}">${image}</a>` : image;
            return wrap(`<div class="vo-public-image-row ${alignmentClass(c.alignment, 'CENTER')}"><span class="vo-public-image-frame" style="${escapeHtml(imageFrameStyle(c))}">${content}</span></div>`);
        }
        case 'FILE': {
            let href = '', fileName = '';
            if (c.mode === 'MANAGER') {
                const file = (content.files || {})[block.id];
                if (!file) return '';
                href = `/offer-files/${encodeURIComponent(token)}/${encodeURIComponent(file.id)}`;
                fileName = file.fileName || c.label || 'Скачать файл';
            } else {
                if (!c.assetUrl) return '';
                href = c.assetUrl;
                fileName = c.assetName || c.label || 'Скачать файл';
            }
            return wrap(`<div class="vo-public-file-row ${alignmentClass(c.alignment, 'LEFT')}"><a class="vo-public-file" href="${safeUrl(href)}" download><span class="vo-public-file-icon">⇩</span><span><b>${escapeHtml(c.label || 'Скачать файл')}</b><small>${escapeHtml(fileName)}</small></span></a></div>`);
        }
        case 'BUTTON': {
            if (!c.href) return '';
            const shape = ['square','rounded','pill'].includes(String(c.shape || '').toLowerCase()) ? String(c.shape).toLowerCase() : 'pill';
            const target = c.newTab ? ' target="_blank" rel="noopener noreferrer"' : '';
            return wrap(`<div class="vo-public-button-row ${alignmentClass(c.alignment, 'CENTER')}"><a class="vo-public-button shape-${shape}" style="--vo-button:${escapeHtml(c.color || '#2f80ed')}" href="${safeUrl(c.href)}"${target}>${escapeHtml(c.text || 'Подробнее')}</a></div>`);
        }
        case 'DIVIDER': {
            const style = ['solid','dashed','dotted'].includes(String(c.style || '').toLowerCase()) ? String(c.style).toLowerCase() : 'solid';
            return wrap(`<hr class="vo-public-divider style-${style}">`);
        }
        default: return '';
    }
}

function bindMainVideo() {
    video.addEventListener('play', () => {
        if (!startedSent) { startedSent = true; sendEvent('VIDEO_STARTED', video.currentTime); }
        resetPlaybackSample(); startPlaybackSampler();
    });
    video.addEventListener('pause', () => { samplePlayback(); stopPlaybackSampler(); sendViewProgress('PAUSE'); });
    video.addEventListener('seeking', resetPlaybackSample);
    video.addEventListener('seeked', resetPlaybackSample);
    video.addEventListener('ratechange', resetPlaybackSample);
    video.addEventListener('waiting', resetPlaybackSample);
    video.addEventListener('ended', () => {
        samplePlayback(); stopPlaybackSampler(); sendViewProgress('ENDED', true);
        if (!completedSent) { completedSent = true; sendEvent('VIDEO_COMPLETED', video.duration || video.currentTime); }
    });
}

document.addEventListener('visibilitychange', () => {
    if (!video) return;
    if (document.visibilityState === 'hidden') { samplePlayback(); sendViewProgress('HIDDEN', true); }
    else if (!video.paused && !video.ended) { resetPlaybackSample(); startPlaybackSampler(); }
});
window.addEventListener('pagehide', () => { if (video) { samplePlayback(); sendViewProgressWithBeacon('HIDDEN'); } });

function startPlaybackSampler() { if (playbackSampler || !viewTrackingActive || !video) return; playbackSampler = setInterval(() => { samplePlayback(); if (Date.now() - lastProgressSentAt >= 5000) sendViewProgress('HEARTBEAT'); }, 1000); }
function stopPlaybackSampler() { if (!playbackSampler) return; clearInterval(playbackSampler); playbackSampler = null; resetPlaybackSample(); }
function samplePlayback() {
    if (!video || !viewTrackingActive || video.paused || video.seeking || video.ended) { resetPlaybackSample(); return; }
    const now = performance.now(), mediaTime = Number(video.currentTime) || 0;
    if (lastSampleWallTime !== null && lastSampleMediaTime !== null) {
        const wallDelta = Math.max(0, (now - lastSampleWallTime) / 1000), mediaDelta = mediaTime - lastSampleMediaTime;
        const allowedMediaDelta = wallDelta * Math.max(1, Number(video.playbackRate) || 1) + 1.25;
        if (mediaDelta >= 0 && mediaDelta <= allowedMediaDelta) watchedSeconds += mediaDelta;
    }
    lastSampleWallTime = now; lastSampleMediaTime = mediaTime;
}
function resetPlaybackSample() { lastSampleWallTime = null; lastSampleMediaTime = null; }
async function sendViewProgress(eventType, force = false) {
    if (!video || !viewTrackingActive || goalReached) return;
    if (!force && Date.now() - lastProgressSentAt < 4000) return;
    lastProgressSentAt = Date.now();
    try {
        const response = await fetch('/api/public/offers/' + token + '/view-progress', {method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(progressPayload(eventType)),keepalive:true});
        if (!response.ok) return;
        const data = await response.json();
        if (data.goalReached || !data.trackingActive) { goalReached = Boolean(data.goalReached); viewTrackingActive = false; stopPlaybackSampler(); }
    } catch (_) {}
}
function sendViewProgressWithBeacon(eventType) { if (!video || !viewTrackingActive || goalReached || !navigator.sendBeacon) return; navigator.sendBeacon('/api/public/offers/' + token + '/view-progress', new Blob([JSON.stringify(progressPayload(eventType))], {type:'application/json'})); }
function progressPayload(eventType) { return {sessionId:viewSessionId,positionSeconds:roundSeconds(video?.currentTime),durationSeconds:video && Number.isFinite(video.duration)?roundSeconds(video.duration):null,watchedSeconds:roundSeconds(watchedSeconds),eventType}; }
function roundSeconds(value) { const number=Number(value)||0; return Math.round(Math.max(0,number)*1000)/1000; }
function createSessionId() { return globalThis.crypto?.randomUUID?.() || Date.now().toString(36)+'-'+Math.random().toString(36).slice(2,14); }
function sendEvent(eventType, playbackPositionSeconds) { fetch('/api/public/offers/' + token + '/events',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({eventType,playbackPositionSeconds}),keepalive:true}).catch(()=>{}); }
function escapeHtml(value) { return String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c])); }
function formatMultiline(value) { return escapeHtml(value).replace(/\n/g,'<br>'); }
function alignmentValue(value, fallback = 'LEFT') {
    const normalized = String(value || fallback).toUpperCase();
    return ['LEFT', 'CENTER', 'RIGHT'].includes(normalized) ? normalized : fallback;
}
function alignmentClass(value, fallback = 'LEFT') { return 'align-' + alignmentValue(value, fallback).toLowerCase(); }
function textInlineStyle(c) {
    const parts = [];
    const family = FONT_STACKS[String(c.fontFamily || 'DEFAULT').toUpperCase()];
    if (family) parts.push(`font-family:${family}`);
    const size = positiveInteger(c.fontSize, 120);
    if (size && size >= 8) parts.push(`font-size:${size}px`);
    parts.push(`font-weight:${c.bold === true ? 700 : 400}`);
    parts.push(`font-style:${c.italic === true ? 'italic' : 'normal'}`);
    parts.push(`text-decoration:${c.underline === true ? 'underline' : 'none'}`);
    parts.push(`text-align:${alignmentValue(c.alignment, 'LEFT').toLowerCase()}`);
    return parts.join(';');
}
function imageFrameStyle(c) {
    const width = positiveInteger(c.width, 5000);
    const height = positiveInteger(c.height, 5000);
    const keep = c.keepAspectRatio !== false;
    const parts = [`width:${width ? width + 'px' : '100%'}`, 'max-width:100%'];
    if (keep && width && height) parts.push(`aspect-ratio:${width}/${height}`);
    else if (!keep && height) parts.push(`height:${height}px`);
    return parts.join(';');
}
function imageContentStyle(c) {
    const height = positiveInteger(c.height, 5000);
    const keep = c.keepAspectRatio !== false;
    return keep || !height ? 'width:100%;height:auto;object-fit:contain' : 'width:100%;height:100%;object-fit:fill';
}
function positiveInteger(value, max) {
    if (value === null || value === undefined || value === '') return null;
    const number = Math.round(Number(value));
    return Number.isFinite(number) && number > 0 && number <= max ? number : null;
}
function safeUrl(value) {
    const url = String(value || '').trim();
    if (url.startsWith('/page-assets/') || url.startsWith('/offer-files/')) return escapeHtml(url);
    if (/^(https?:|tel:|mailto:)/i.test(url)) return escapeHtml(url);
    return '#';
}

loadOffer();
