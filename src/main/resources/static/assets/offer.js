const token = location.pathname.split('/').filter(Boolean).pop();
const pageHost = document.getElementById('offer-page');
const previewMode = new URLSearchParams(location.search).get('preview') === '1';
const GRID_COLUMNS = 12;
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
    const rows = groupRows(blocks);
    pageHost.innerHTML = rows.map(row => {
        const cells = row.blocks.map(block => renderBlock(block, data, content)).filter(Boolean).join('');
        return cells ? `<div class="vo-public-row" data-row-id="${escapeHtml(row.id)}">${cells}</div>` : '';
    }).join('');
    if (!pageHost.innerHTML.trim()) pageHost.innerHTML = '<div class="public-page-error"><h1>Страница не настроена</h1></div>';
    mountEmbeds(blocks);
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

function groupRows(blocks) {
    const rows = [];
    const byId = new Map();
    blocks.forEach((block, index) => {
        const rowId = String(block?.rowId || `legacy-row-${index}`);
        let row = byId.get(rowId);
        if (!row) { row = {id: rowId, blocks: []}; byId.set(rowId, row); rows.push(row); }
        row.blocks.push(block);
    });
    return rows;
}

function renderBlock(block, data, content) {
    const c = block.config || {};
    const visibility = block.visibility === 'DESKTOP' ? 'vo-public-desktop-only' : block.visibility === 'MOBILE' ? 'vo-public-mobile-only' : '';
    const span = normalizedSpan(block.span);
    const wrap = inner => `<section class="vo-public-block ${visibility}" style="--vo-span:${span}" data-block-id="${escapeHtml(block.id || '')}" data-block-type="${escapeHtml(block.type || '')}">${inner}</section>`;
    switch (block.type) {
        case 'HEADER': {
            // Legacy offer snapshots keep their original header forever. New templates no longer create HEADER blocks.
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
            const imageLink = c.href ? `<a class="vo-public-image-link" href="${safeUrl(c.href)}">${image}</a>` : image;
            return wrap(`<div class="vo-public-image-row ${alignmentClass(c.alignment, 'CENTER')}"><span class="vo-public-image-frame" style="${escapeHtml(imageFrameStyle(c))}">${imageLink}</span></div>`);
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
            const shape = optionClass(c.shape, ['SQUARE','ROUNDED','PILL'], 'PILL');
            const depth = optionClass(c.depth, ['FLAT','SUBTLE','RAISED','DEEP'], 'FLAT');
            const shadow = optionClass(c.shadow, ['NONE','SOFT','MEDIUM','STRONG'], 'NONE');
            const hover = optionClass(c.hoverAnimation, ['NONE','LIFT','GROW','GLOW','BRIGHTEN'], 'LIFT');
            const press = optionClass(c.clickAnimation, ['NONE','PRESS','SHRINK','BOUNCE'], 'PRESS');
            const target = c.newTab ? ' target="_blank" rel="noopener noreferrer"' : '';
            const style = `--vo-button:${c.color || '#2f80ed'};${textInlineStyle(c, 'CENTER')}`;
            return wrap(`<div class="vo-public-button-row ${alignmentClass(c.alignment, 'CENTER')}"><a class="vo-public-button shape-${shape} depth-${depth} shadow-${shadow} hover-${hover} press-${press}" style="${escapeHtml(style)}" href="${safeUrl(c.href)}"${target}>${escapeHtml(c.text || 'Подробнее')}</a></div>`);
        }
        case 'ICON_TEXT': {
            const text = c.text || '';
            if (!text && !c.icon) return '';
            const inner = `<span class="vo-public-icon" style="color:${escapeHtml(c.iconColor || '#2f80ed')};width:${positiveInteger(c.iconSize,96)||24}px;height:${positiveInteger(c.iconSize,96)||24}px">${iconSvg(c.icon)}</span><span class="vo-public-icon-copy" style="color:${escapeHtml(c.textColor || '#344f5f')};${escapeHtml(textInlineStyle(c))}">${escapeHtml(text)}</span>`;
            const contentHtml = c.href ? `<a class="vo-public-icon-link" href="${safeUrl(c.href)}">${inner}</a>` : `<span class="vo-public-icon-link">${inner}</span>`;
            return wrap(`<div class="vo-public-icon-text ${alignmentClass(c.alignment, 'LEFT')}">${contentHtml}</div>`);
        }
        case 'DIVIDER': {
            const style = optionClass(c.style, ['SOLID','DASHED','DOTTED'], 'SOLID');
            return wrap(`<hr class="vo-public-divider style-${style}">`);
        }
        case 'EMBED': {
            if (!String(c.code || '').trim()) return '';
            return wrap(`<div class="vo-public-embed" data-vo-embed="${escapeHtml(block.id || '')}"></div>`);
        }
        default: return '';
    }
}

function mountEmbeds(blocks) {
    const mobile = window.matchMedia('(max-width: 700px)').matches;
    blocks.filter(block => block?.type === 'EMBED').forEach(block => {
        const host = pageHost.querySelector(`[data-vo-embed="${cssEscapeValue(block.id || '')}"]`);
        if (!host) return;
        const visibility = String(block.visibility || 'ALL').toUpperCase();
        if ((visibility === 'DESKTOP' && mobile) || (visibility === 'MOBILE' && !mobile)) return;
        if (previewMode) return;
        const c = block.config || {};
        const code = String(c.code || '');
        if (!code.trim()) return;
        if (String(c.codeType || 'HTML').toUpperCase() === 'JAVASCRIPT') {
            const script = document.createElement('script');
            script.textContent = code;
            host.appendChild(script);
            return;
        }
        const template = document.createElement('template');
        template.innerHTML = code;
        host.appendChild(template.content.cloneNode(true));
        host.querySelectorAll('script').forEach(oldScript => {
            const script = document.createElement('script');
            for (const attribute of oldScript.attributes) script.setAttribute(attribute.name, attribute.value);
            script.textContent = oldScript.textContent;
            oldScript.replaceWith(script);
        });
    });
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
function textInlineStyle(c, fallbackAlignment = 'LEFT') {
    const parts = [];
    const family = FONT_STACKS[String(c.fontFamily || 'DEFAULT').toUpperCase()];
    if (family) parts.push(`font-family:${family}`);
    const size = positiveInteger(c.fontSize, 120);
    if (size && size >= 8) parts.push(`font-size:${size}px`);
    parts.push(`font-weight:${c.bold === true ? 700 : 400}`);
    parts.push(`font-style:${c.italic === true ? 'italic' : 'normal'}`);
    parts.push(`text-decoration:${c.underline === true ? 'underline' : 'none'}`);
    parts.push(`text-align:${alignmentValue(c.alignment, fallbackAlignment).toLowerCase()}`);
    return parts.join(';');
}
function imageFrameStyle(c) {
    const width = positiveInteger(c.width, 5000);
    const height = positiveInteger(c.height, 5000);
    const viewport = positiveInteger(c.viewportHeight, 3000);
    const keep = c.keepAspectRatio !== false;
    const parts = [`width:${width ? width + 'px' : '100%'}`, 'max-width:100%', 'position:relative', 'overflow:hidden'];
    if (viewport) parts.push(`height:${viewport}px`);
    else if (keep && width && height) parts.push(`aspect-ratio:${width}/${height}`);
    else if (!keep && height) parts.push(`height:${height}px`);
    return parts.join(';');
}
function imageContentStyle(c) {
    const viewport = positiveInteger(c.viewportHeight, 3000);
    const height = positiveInteger(c.height, 5000);
    const keep = c.keepAspectRatio !== false;
    if (viewport) {
        if (keep) return 'position:absolute;left:50%;top:50%;transform:translate(-50%,-50%);width:100%;height:auto;max-width:none;max-height:none;object-fit:contain';
        return `position:absolute;left:50%;top:50%;transform:translate(-50%,-50%);width:100%;height:${height ? height + 'px' : 'auto'};max-width:none;max-height:none;object-fit:fill`;
    }
    return keep || !height ? 'width:100%;height:auto;object-fit:contain' : 'width:100%;height:100%;object-fit:fill';
}
function normalizedSpan(value) {
    const n = Math.round(Number(value));
    return Number.isFinite(n) && n >= 3 && n <= GRID_COLUMNS ? n : GRID_COLUMNS;
}
function optionClass(value, allowed, fallback) {
    const normalized = String(value || fallback).toUpperCase();
    return (allowed.includes(normalized) ? normalized : fallback).toLowerCase();
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
function iconSvg(name) {
    const icon = String(name || 'PHONE').toUpperCase();
    const paths = {
        PHONE:'<path d="M7 3.5 9.3 8l-2.1 1.7a15 15 0 0 0 7.1 7.1l1.7-2.1 4.5 2.3-.8 3.5c-.2.8-.9 1.4-1.7 1.4C9.2 21.5 2.5 14.8 2.1 6c0-.8.6-1.5 1.4-1.7L7 3.5Z"/>',
        MAIL:'<rect x="3" y="5" width="18" height="14" rx="2"/><path d="m4 7 8 6 8-6"/>',
        LOCATION:'<path d="M12 22s7-6 7-13a7 7 0 1 0-14 0c0 7 7 13 7 13Z"/><circle cx="12" cy="9" r="2.5"/>',
        LINK:'<path d="M10 13a5 5 0 0 0 7.1 0l2-2A5 5 0 0 0 12 3.9L10.9 5M14 11a5 5 0 0 0-7.1 0l-2 2A5 5 0 0 0 12 20.1l1.1-1.1"/>',
        MESSAGE:'<path d="M4 4h16v12H8l-4 4V4Z"/>',
        CLOCK:'<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
        USER:'<circle cx="12" cy="8" r="4"/><path d="M4 21a8 8 0 0 1 16 0"/>',
        CHECK:'<path d="m4 12 5 5L20 6"/>',
        INFO:'<circle cx="12" cy="12" r="9"/><path d="M12 11v6M12 7h.01"/>'
    };
    return `<svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">${paths[icon] || paths.PHONE}</svg>`;
}
function cssEscapeValue(value) {
    if (globalThis.CSS?.escape) return CSS.escape(String(value));
    return String(value).replace(/[^A-Za-z0-9_-]/g, '\\$&');
}

loadOffer();
