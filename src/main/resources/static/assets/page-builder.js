(() => {
'use strict';

const GRID_COLUMNS = 12;
const MIN_BLOCK_SPAN = 3;
const WIDTH_PRESETS = [12, 9, 6, 4, 3];
const BLOCKS = [
    {type:'VIDEO', label:'Видео', icon:'▶', max:5, description:'Основной оффер или отдельное видео'},
    {type:'TEXT', label:'Текст', icon:'T', max:5, description:'Статический или заполняемый менеджером'},
    {type:'IMAGE', label:'Изображение', icon:'▧', max:5, description:'Баннер, фото или иллюстрация'},
    {type:'FILE', label:'Файл', icon:'⇩', max:5, description:'Статический или файл менеджера'},
    {type:'BUTTON', label:'Кнопка', icon:'↗', max:5, description:'Ссылка, звонок или мессенджер'},
    {type:'ICON_TEXT', label:'Иконка + текст', icon:'☎', max:5, description:'Телефон, e-mail, адрес или подпись'},
    {type:'DIVIDER', label:'Разделитель', icon:'—', max:5, description:'Визуальное разделение блоков'},
    {type:'EMBED', label:'Вставка', icon:'</>', max:5, description:'HTML / JavaScript, метрика или pixel'}
];

const FONT_OPTIONS = [
    ['DEFAULT', 'По умолчанию'],
    ['ARIAL', 'Arial'],
    ['VERDANA', 'Verdana'],
    ['GEORGIA', 'Georgia'],
    ['TIMES_NEW_ROMAN', 'Times New Roman'],
    ['TREBUCHET_MS', 'Trebuchet MS'],
    ['COURIER_NEW', 'Courier New']
];

const FONT_STACKS = {
    ARIAL: 'Arial, sans-serif',
    VERDANA: 'Verdana, sans-serif',
    GEORGIA: 'Georgia, serif',
    TIMES_NEW_ROMAN: '"Times New Roman", Times, serif',
    TREBUCHET_MS: '"Trebuchet MS", sans-serif',
    COURIER_NEW: '"Courier New", monospace'
};

const ICONS = [
    ['PHONE','Телефон'], ['MAIL','Почта'], ['LOCATION','Адрес'], ['LINK','Ссылка'],
    ['MESSAGE','Сообщение'], ['CLOCK','Время'], ['USER','Контакт'], ['CHECK','Галочка'], ['INFO','Информация']
];

const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const attr = esc;
const bool = value => value === true || String(value).toLowerCase() === 'true';
const uid = prefix => `${prefix || 'b'}${globalThis.crypto?.randomUUID?.().replaceAll('-','') || Date.now().toString(36)+Math.random().toString(36).slice(2)}`;

class VideoOfferPageBuilder {
    constructor(options) {
        this.root = options.root;
        this.theme = options.theme || 'dark';
        this.uploadAsset = options.uploadAsset;
        this.saveTemplate = options.saveTemplate;
        this.template = normalizeClientTemplate(options.template);
        this.selectedId = null;
        this.panelMode = 'palette';
        this.busy = false;
        this.suppressPaletteClickUntil = 0;
        this.onSaved = options.onSaved || (()=>{});
        this.dragIndicator = null;
        this.render();
    }

    setTemplate(template) {
        this.template = normalizeClientTemplate(template);
        this.selectedId = null;
        this.panelMode = 'palette';
        this.render();
    }

    getTemplate() { return JSON.parse(JSON.stringify(this.template)); }

    render() {
        if (!this.root) return;
        this.root.innerHTML = `
          <div class="vo-page-builder ${this.theme === 'light' ? 'is-light' : 'is-dark'}">
            <aside class="vo-builder-sidebar">${this.renderSidebarInner()}</aside>
            <section class="vo-builder-workspace">
              <header class="vo-builder-toolbar">
                <div><span class="vo-builder-eyebrow">СТРАНИЦА ВИДЕООФФЕРА</span><b>Шаблон клиента</b></div>
                <button type="button" class="vo-builder-save" data-pb-save ${this.busy ? 'disabled' : ''}>${this.busy ? 'Сохраняю…' : 'Сохранить шаблон'}</button>
              </header>
              <div class="vo-builder-stage">
                <div class="vo-builder-page" data-pb-canvas>${this.renderCanvas()}</div>
              </div>
            </section>
          </div>`;
        this.bind();
    }

    renderSidebarInner() {
        return `
          <div class="vo-builder-sidebar-head">
            <div><span class="vo-builder-eyebrow">КОНСТРУКТОР</span><b>${this.panelMode === 'properties' ? 'Параметры блока' : 'Элементы'}</b></div>
            ${this.panelMode === 'properties' ? '<button type="button" class="vo-builder-back" data-pb-back>← Элементы</button>' : ''}
          </div>
          <div class="vo-builder-sidebar-body">${this.panelMode === 'properties' ? this.renderProperties() : this.renderPalette()}</div>`;
    }

    renderPalette() {
        const counts = this.counts();
        return `<div class="vo-builder-palette">${BLOCKS.map(meta => {
            const count = counts[meta.type] || 0;
            const disabled = count >= meta.max;
            return `<button type="button" class="vo-palette-item ${disabled ? 'is-disabled' : ''}" data-pb-palette="${meta.type}" ${disabled ? 'aria-disabled="true"' : ''}>
                <span class="vo-palette-icon">${esc(meta.icon)}</span><span class="vo-palette-copy"><b>${esc(meta.label)}</b><small>${esc(meta.description)}</small></span><span class="vo-palette-count">${count}/${meta.max}</span>
            </button>`;
        }).join('')}</div>`;
    }

    renderCanvas() {
        const rows = this.layoutRows();
        if (!rows.length) return '<div class="vo-builder-empty">Перетащите элемент сюда</div>';
        return rows.map(row => this.renderRow(row)).join('');
    }

    renderRow(row) {
        const used = row.blocks.reduce((sum, block) => sum + normalizedSpan(block.span), 0);
        const free = Math.max(0, GRID_COLUMNS - used);
        return `<div class="vo-builder-row" data-pb-row="${attr(row.id)}">
            ${row.blocks.map(block => this.renderBlockCard(block)).join('')}
            ${free >= MIN_BLOCK_SPAN ? `<div class="vo-builder-row-free" style="--pb-free-span:${free}" data-pb-free="${attr(row.id)}"><span>Свободно ${widthLabel(free)}</span></div>` : ''}
        </div>`;
    }

    renderBlockCard(block) {
        const selected = block.id === this.selectedId;
        const locked = block.type === 'VIDEO' && block.config?.source === 'MAIN';
        const uploadable = this.supportsDirectUpload(block);
        return `<article class="vo-builder-block ${selected ? 'is-selected' : ''} ${verticalAlignmentClass(block.config?.verticalAlignment, 'TOP')}" style="--pb-span:${normalizedSpan(block.span)}" data-pb-block="${attr(block.id)}">
            <div class="vo-builder-block-chrome" title="Перетащить блок">
              <span class="vo-builder-drag-handle" aria-hidden="true">⋮⋮</span>
              <span class="vo-builder-block-name">${this.blockLabel(block)}</span>
              <span class="vo-builder-block-width">${widthLabel(block.span)}</span>
              <span class="vo-builder-visibility">${visibilityLabel(block.visibility)}</span>
              <button type="button" class="vo-builder-block-delete" data-pb-delete="${attr(block.id)}" ${locked ? 'disabled title="Основное видео обязательно"' : 'title="Удалить"'}>✕</button>
            </div>
            <div class="vo-builder-block-preview ${uploadable ? 'is-uploadable' : ''}"${uploadable ? ' title="Двойной клик — выбрать файл"' : ''}>${this.renderPreview(block)}</div>
        </article>`;
    }

    renderPreview(block) {
        const c = block.config || {};
        switch (block.type) {
            case 'VIDEO': return `<div class="vo-preview-video"><span class="vo-preview-play">▶</span><div><b>${c.source === 'MAIN' ? 'Видеооффер' : esc(c.assetName || 'Дополнительное видео')}</b>${c.title ? `<small>${esc(c.title)}</small>` : ''}</div></div>`;
            case 'TEXT': {
                const sample = c.mode === 'MANAGER' ? `[Менеджер заполняет: ${c.label || 'Текст'}]` : (c.text || 'Текстовый блок');
                const tag = c.style === 'HEADING' ? 'h3' : 'p';
                return `<${tag} class="vo-preview-text ${c.style === 'NOTE' ? 'is-note' : ''}" style="${attr(textInlineStyle(c))}">${esc(sample)}</${tag}>`;
            }
            case 'IMAGE': {
                if (!c.assetUrl) return `<div class="vo-preview-placeholder ${alignmentClass(c.alignment, 'CENTER')}">Изображение</div>`;
                return `<div class="vo-preview-image-row ${alignmentClass(c.alignment, 'CENTER')}"><div class="vo-preview-image-frame" style="${attr(imageFrameStyle(c))}"><img class="radius-${String(c.radius || 'LARGE').toLowerCase()}" style="${attr(imageContentStyle(c))}" src="${attr(c.assetUrl)}" alt=""></div></div>`;
            }
            case 'FILE': return `<div class="vo-preview-file-row ${alignmentClass(c.alignment, 'LEFT')}"><div class="vo-preview-file"><span>⇩</span><div><b>${esc(c.label || 'Скачать файл')}</b><small>${c.mode === 'MANAGER' ? (c.required ? 'Менеджер · обязательно' : 'Менеджер · необязательно') : esc(c.assetName || 'Статический файл')}</small></div></div></div>`;
            case 'BUTTON': return `<div class="vo-preview-button-wrap ${alignmentClass(c.alignment, 'CENTER')}"><span class="vo-preview-button shape-${String(c.shape || 'PILL').toLowerCase()} depth-${String(c.depth || 'FLAT').toLowerCase()} shadow-${String(c.shadow || 'NONE').toLowerCase()} hover-${String(c.hoverAnimation || 'LIFT').toLowerCase()} press-${String(c.clickAnimation || 'PRESS').toLowerCase()}" style="--pb-button:${attr(c.color || '#2f80ed')};${attr(textInlineStyle(c, 'CENTER'))}">${esc(c.text || 'Подробнее')}</span></div>`;
            case 'ICON_TEXT': return `<div class="vo-preview-icon-text ${alignmentClass(c.alignment, 'LEFT')}"><span class="vo-preview-icon" style="color:${attr(c.iconColor || '#2f80ed')};width:${positiveInteger(c.iconSize,96)||24}px;height:${positiveInteger(c.iconSize,96)||24}px">${iconSvg(c.icon)}</span><span class="vo-preview-icon-copy" style="color:${attr(c.textColor || '#344f5f')};${attr(textInlineStyle(c))}">${esc(c.text || 'Телефон или подпись')}</span></div>`;
            case 'DIVIDER': return `<div class="vo-preview-divider style-${String(c.style || 'SOLID').toLowerCase()}"></div>`;
            case 'EMBED': return `<div class="vo-preview-embed"><b>${c.codeType === 'JAVASCRIPT' ? 'JavaScript' : 'HTML / tracking code'}</b><small>${esc(codeSummary(c.code))}</small><span>Код не выполняется внутри конструктора</span></div>`;
            default: return '';
        }
    }

    renderProperties() {
        const block = this.selected();
        if (!block) { this.panelMode='palette'; return this.renderPalette(); }
        const c = block.config || {};
        const common = `${widthField(block, this.maxSpanForBlock(block))}<label class="vo-builder-field">Видимость<select data-pb-prop="visibility"><option value="ALL" ${block.visibility==='ALL'?'selected':''}>Компьютер и телефон</option><option value="DESKTOP" ${block.visibility==='DESKTOP'?'selected':''}>Только компьютер</option><option value="MOBILE" ${block.visibility==='MOBILE'?'selected':''}>Только телефон</option></select></label>${verticalAlignmentField(c.verticalAlignment, 'TOP')}`;
        let specific='';
        if (block.type === 'VIDEO') specific = `
            <label class="vo-builder-field">Источник<select data-pb-config="source"><option value="MAIN" ${c.source==='MAIN'?'selected':''}>Основной видеооффер</option><option value="STATIC" ${c.source==='STATIC'?'selected':''}>Загруженное видео</option></select></label>
            ${inputField('Подпись','title',c.title,'text')}
            ${c.source === 'STATIC' ? uploadField('Видеофайл MP4 / WebM','VIDEO',c.assetName,'asset') : ''}`;
        if (block.type === 'TEXT') specific = `
            <label class="vo-builder-field">Источник текста<select data-pb-config="mode"><option value="STATIC" ${c.mode==='STATIC'?'selected':''}>Статический текст</option><option value="MANAGER" ${c.mode==='MANAGER'?'selected':''}>Редактирует менеджер</option></select></label>
            <label class="vo-builder-field">Вид текста<select data-pb-config="style"><option value="HEADING" ${c.style==='HEADING'?'selected':''}>Заголовок</option><option value="PARAGRAPH" ${c.style==='PARAGRAPH'?'selected':''}>Обычный текст</option><option value="NOTE" ${c.style==='NOTE'?'selected':''}>Выделенный текст</option></select></label>
            ${c.mode === 'STATIC' ? textareaField('Текст','text',c.text,6) : `${inputField('Название поля для менеджера','label',c.label,'text')}${inputField('Подсказка','placeholder',c.placeholder,'text')}${checkboxField('Обязательное поле','required',c.required)}`}
            ${typographyFields(c)}
            ${alignmentField(c.alignment, 'LEFT')}`;
        if (block.type === 'IMAGE') specific = `
            ${uploadField('Изображение','IMAGE',c.assetName,'asset')}
            ${inputField('Описание изображения','alt',c.alt,'text')}
            ${inputField('Ссылка при клике','href',c.href,'text','https://...')}
            <label class="vo-builder-field">Скругление<select data-pb-config="radius"><option value="NONE" ${c.radius==='NONE'?'selected':''}>Без скругления</option><option value="SMALL" ${c.radius==='SMALL'?'selected':''}>Небольшое</option><option value="LARGE" ${c.radius==='LARGE'?'selected':''}>Большое</option></select></label>
            ${alignmentField(c.alignment, 'CENTER')}
            ${imageSizeFields(c)}
            ${numberField('Высота видимой области, px','viewportHeight',c.viewportHeight,40,3000,'Авто — показать целиком')}
            <div class="vo-builder-hint">Если задана высота видимой области, верх и низ изображения обрезаются, а центральная часть остаётся по центру.</div>`;
        if (block.type === 'FILE') specific = `
            <label class="vo-builder-field">Источник файла<select data-pb-config="mode"><option value="STATIC" ${c.mode==='STATIC'?'selected':''}>Загружает администратор</option><option value="MANAGER" ${c.mode==='MANAGER'?'selected':''}>Загружает менеджер</option></select></label>
            ${inputField('Подпись','label',c.label,'text')}
            ${c.mode === 'STATIC' ? uploadField('Документ','FILE',c.assetName,'asset') : checkboxField('Файл обязателен при создании оффера','required',c.required)}
            ${alignmentField(c.alignment, 'LEFT')}`;
        if (block.type === 'BUTTON') specific = `
            ${inputField('Текст кнопки','text',c.text,'text')}
            ${inputField('Ссылка','href',c.href,'text','https://, tel:, mailto:')}
            ${inputField('Цвет','color',c.color || '#2f80ed','color')}
            <label class="vo-builder-field">Форма<select data-pb-config="shape"><option value="SQUARE" ${c.shape==='SQUARE'?'selected':''}>Прямоугольная</option><option value="ROUNDED" ${c.shape==='ROUNDED'?'selected':''}>Скруглённая</option><option value="PILL" ${c.shape==='PILL'?'selected':''}>Капсула</option></select></label>
            <label class="vo-builder-field">Объём<select data-pb-config="depth"><option value="FLAT" ${c.depth==='FLAT'?'selected':''}>Плоская</option><option value="SUBTLE" ${c.depth==='SUBTLE'?'selected':''}>Лёгкий объём</option><option value="RAISED" ${c.depth==='RAISED'?'selected':''}>Приподнятая</option><option value="DEEP" ${c.depth==='DEEP'?'selected':''}>Выраженный 3D</option></select></label>
            <label class="vo-builder-field">Тень<select data-pb-config="shadow"><option value="NONE" ${c.shadow==='NONE'?'selected':''}>Нет</option><option value="SOFT" ${c.shadow==='SOFT'?'selected':''}>Мягкая</option><option value="MEDIUM" ${c.shadow==='MEDIUM'?'selected':''}>Средняя</option><option value="STRONG" ${c.shadow==='STRONG'?'selected':''}>Сильная</option></select></label>
            <label class="vo-builder-field">Анимация при наведении<select data-pb-config="hoverAnimation"><option value="NONE" ${c.hoverAnimation==='NONE'?'selected':''}>Нет</option><option value="LIFT" ${c.hoverAnimation==='LIFT'?'selected':''}>Подъём</option><option value="GROW" ${c.hoverAnimation==='GROW'?'selected':''}>Увеличение</option><option value="GLOW" ${c.hoverAnimation==='GLOW'?'selected':''}>Свечение</option><option value="BRIGHTEN" ${c.hoverAnimation==='BRIGHTEN'?'selected':''}>Подсветка</option></select></label>
            <label class="vo-builder-field">Анимация при нажатии<select data-pb-config="clickAnimation"><option value="NONE" ${c.clickAnimation==='NONE'?'selected':''}>Нет</option><option value="PRESS" ${c.clickAnimation==='PRESS'?'selected':''}>Нажатие</option><option value="SHRINK" ${c.clickAnimation==='SHRINK'?'selected':''}>Сжатие</option><option value="BOUNCE" ${c.clickAnimation==='BOUNCE'?'selected':''}>Пружина</option></select></label>
            ${typographyFields(c)}
            ${alignmentField(c.alignment, 'CENTER')}
            ${checkboxField('Открывать в новой вкладке','newTab',c.newTab)}`;
        if (block.type === 'ICON_TEXT') specific = `
            <label class="vo-builder-field">Иконка<select data-pb-config="icon">${ICONS.map(([key,label])=>`<option value="${key}" ${String(c.icon||'PHONE').toUpperCase()===key?'selected':''}>${esc(label)}</option>`).join('')}</select></label>
            ${inputField('Текст','text',c.text,'text')}
            ${inputField('Ссылка','href',c.href,'text','tel:, mailto:, https://...')}
            <div class="vo-builder-dimension-row">${inputField('Цвет иконки','iconColor',c.iconColor || '#2f80ed','color')}${numberField('Размер иконки, px','iconSize',c.iconSize,12,96,'24')}</div>
            ${inputField('Цвет текста','textColor',c.textColor || '#344f5f','color')}
            ${typographyFields(c)}
            ${alignmentField(c.alignment, 'LEFT')}`;
        if (block.type === 'DIVIDER') specific = `<label class="vo-builder-field">Линия<select data-pb-config="style"><option value="SOLID" ${c.style==='SOLID'?'selected':''}>Сплошная</option><option value="DASHED" ${c.style==='DASHED'?'selected':''}>Штриховая</option><option value="DOTTED" ${c.style==='DOTTED'?'selected':''}>Точечная</option></select></label>`;
        if (block.type === 'EMBED') specific = `
            <label class="vo-builder-field">Тип вставки<select data-pb-config="codeType"><option value="HTML" ${c.codeType==='HTML'?'selected':''}>HTML / tracking snippet</option><option value="JAVASCRIPT" ${c.codeType==='JAVASCRIPT'?'selected':''}>JavaScript</option></select></label>
            ${textareaField('Код','code',c.code,12)}
            <div class="vo-builder-hint">Код сохраняется в шаблоне, но не выполняется в конструкторе. На публичной странице HTML/JS выполняется только в обычном режиме; предпросмотр его не запускает.</div>`;
        return `<div class="vo-builder-properties"><div class="vo-builder-selected-title"><span>${esc(BLOCKS.find(x=>x.type===block.type)?.icon || '□')}</span><div><b>${this.blockLabel(block)}</b><small>${widthLabel(block.span)} строки</small></div></div>${common}${specific}</div>`;
    }

    bind() {
        this.bindSave();
        this.bindSidebar();
        this.bindCanvas();
    }

    bindSave() {
        this.root.querySelector('[data-pb-save]')?.addEventListener('click',()=>this.save());
    }

    bindSidebar() {
        this.root.querySelector('[data-pb-back]')?.addEventListener('click',()=>{this.panelMode='palette';this.renderSidebar();});
        this.root.querySelectorAll('[data-pb-palette]').forEach(item => {
            item.addEventListener('click', () => {
                if (Date.now() < this.suppressPaletteClickUntil) return;
                if (!item.classList.contains('is-disabled')) this.addBlock(item.dataset.pbPalette);
            });
            item.addEventListener('pointerdown', e => this.palettePointerDown(e,item));
        });
        this.root.querySelectorAll('[data-pb-config]').forEach(input=>{
            const key = input.dataset.pbConfig;
            const structural = key === 'source' || key === 'mode';
            const eventName = (input.tagName === 'SELECT' || input.type === 'checkbox') ? 'change' : 'input';
            input.addEventListener(eventName,()=>this.updateConfig(key,input.type==='checkbox'?input.checked:input.value,structural));
        });
        this.root.querySelectorAll('[data-pb-choice-config]').forEach(button=>button.addEventListener('click',()=>{
            const key=button.dataset.pbChoiceConfig;
            const value=button.dataset.pbValue;
            this.updateConfig(key,value,false);
            button.parentElement?.querySelectorAll('[data-pb-choice-config]').forEach(item=>item.classList.toggle('is-active',item.dataset.pbValue===value));
        }));
        this.root.querySelectorAll('[data-pb-toggle-config]').forEach(button=>button.addEventListener('click',()=>{
            const key=button.dataset.pbToggleConfig;
            const current=bool(this.selected()?.config?.[key]);
            this.updateConfig(key,!current,false);
            button.classList.toggle('is-active',!current);
        }));
        this.root.querySelector('[data-pb-layout-span]')?.addEventListener('change',e=>this.updateSpan(Number(e.target.value)));
        this.root.querySelector('[data-pb-prop="visibility"]')?.addEventListener('change',e=>this.updateVisibility(e.target.value));
        this.root.querySelectorAll('[data-pb-upload]').forEach(input=>input.addEventListener('change',()=>this.handleAssetUpload(input)));
        this.root.querySelectorAll('[data-pb-clear-asset]').forEach(btn=>btn.addEventListener('click',()=>this.clearAsset(btn.dataset.pbClearAsset)));
    }

    bindCanvas() {
        this.root.querySelectorAll('[data-pb-block]').forEach(item => {
            item.addEventListener('click',e=>{ if(e.target.closest('[data-pb-delete]')) return; this.selectBlock(item.dataset.pbBlock); });
            item.querySelector('.vo-builder-block-chrome')?.addEventListener('pointerdown',e=>this.canvasPointerDown(e,item));
            item.querySelector('.vo-builder-block-preview')?.addEventListener('dblclick',e=>this.openAssetPicker(item.dataset.pbBlock,e));
        });
        this.root.querySelectorAll('[data-pb-delete]').forEach(btn=>btn.addEventListener('click',e=>{e.stopPropagation();if(!btn.disabled)this.deleteBlock(btn.dataset.pbDelete);}));
    }

    renderSidebar() {
        const sidebar=this.root.querySelector('.vo-builder-sidebar');
        if(!sidebar)return;
        sidebar.innerHTML=this.renderSidebarInner();
        this.bindSidebar();
    }

    renderCanvasInPlace() {
        const canvas=this.root.querySelector('[data-pb-canvas]');
        if(!canvas)return;
        canvas.innerHTML=this.renderCanvas();
        this.bindCanvas();
    }

    renderBusyState() {
        const button=this.root.querySelector('[data-pb-save]');
        if(!button)return;
        button.disabled=this.busy;
        button.textContent=this.busy?'Сохраняю…':'Сохранить шаблон';
    }

    layoutRows() {
        const rows=[];
        const byId=new Map();
        this.template.blocks.forEach(block=>{
            const rowId=block.rowId || uid('r');
            block.rowId=rowId;
            block.span=normalizedSpan(block.span);
            let row=byId.get(rowId);
            if(!row){row={id:rowId,blocks:[]};byId.set(rowId,row);rows.push(row);}
            row.blocks.push(block);
        });
        return rows;
    }

    setRows(rows) {
        const flat=[];
        rows.filter(row=>row.blocks.length).forEach(row=>row.blocks.forEach(block=>{block.rowId=row.id;block.span=normalizedSpan(block.span);flat.push(block);}));
        this.template.blocks=flat;
    }

    counts(){ const out={}; for(const b of this.template.blocks) out[b.type]=(out[b.type]||0)+1; return out; }
    selected(){ return this.template.blocks.find(b=>b.id===this.selectedId) || null; }
    blockLabel(block){ const label=BLOCKS.find(x=>x.type===block.type)?.label || block.type; if(block.type==='VIDEO'&&block.config?.source==='MAIN')return label+' · основной'; return label; }

    addBlock(type) {
        const meta=BLOCKS.find(x=>x.type===type); if(!meta)return;
        if((this.counts()[type]||0)>=meta.max)return this.flashLimit(type);
        const block=defaultBlock(type,this.template.blocks);
        block.rowId=uid('r');
        block.span=GRID_COLUMNS;
        this.template.blocks.push(block);
        this.selectedId=block.id;
        this.panelMode='properties';
        this.renderCanvasInPlace();
        this.renderSidebar();
    }

    deleteBlock(id){
        const i=this.template.blocks.findIndex(b=>b.id===id);
        if(i<0)return;
        this.template.blocks.splice(i,1);
        if(this.selectedId===id){this.selectedId=null;this.panelMode='palette';}
        this.renderCanvasInPlace();
        this.renderSidebar();
    }

    selectBlock(id){
        if(!this.template.blocks.some(b=>b.id===id))return;
        const changed=this.selectedId!==id||this.panelMode!=='properties';
        this.selectedId=id;
        this.panelMode='properties';
        this.root.querySelectorAll('[data-pb-block]').forEach(item=>item.classList.toggle('is-selected',item.dataset.pbBlock===id));
        if(changed)this.renderSidebar();
    }

    maxSpanForBlock(block) {
        if(!block)return GRID_COLUMNS;
        const row=this.layoutRows().find(item=>item.id===block.rowId);
        const others=(row?.blocks||[]).filter(item=>item.id!==block.id).reduce((sum,item)=>sum+normalizedSpan(item.span),0);
        return Math.max(MIN_BLOCK_SPAN, GRID_COLUMNS-others);
    }

    updateSpan(span){
        const block=this.selected();
        if(!block)return;
        const desired=normalizedSpan(span);
        const max=this.maxSpanForBlock(block);
        if(desired>max){
            const input=this.root.querySelector('[data-pb-layout-span]');
            if(input)input.value=String(block.span);
            return;
        }
        block.span=desired;
        this.renderCanvasInPlace();
        this.renderSidebar();
    }

    updateConfig(key,value,structural=false){
        const b=this.selected();if(!b)return;
        b.config=b.config||{};
        if(b.type==='IMAGE'&&(key==='width'||key==='height')){
            this.updateImageDimension(b,key,value);
            return;
        }
        if(key==='source'&&b.type==='VIDEO'&&value==='MAIN'){
            this.template.blocks.filter(x=>x.type==='VIDEO'&&x.id!==b.id&&x.config?.source==='MAIN').forEach(x=>{x.config.source='STATIC';x.config.assetUrl=null;x.config.assetName='';});
        }
        b.config[key]=value;
        if(key==='mode'&&b.type==='TEXT'&&value==='STATIC')delete b.config.required;
        if(key==='keepAspectRatio'&&b.type==='IMAGE'&&bool(value))this.syncImageDimensionsToRatio(b);
        if(structural){
            this.renderCanvasInPlace();
            this.renderSidebar();
            return;
        }
        this.refreshSelectedCard();
        if(key==='keepAspectRatio'&&b.type==='IMAGE')this.syncImagePropertyInputs(b);
    }

    updateImageDimension(block,key,value){
        const dimension=positiveInteger(value,5000);
        block.config[key]=dimension;
        if(bool(block.config.keepAspectRatio??true)){
            if(dimension===null){
                block.config.width=null;
                block.config.height=null;
            }else{
                const ratio=this.resolveImageRatio(block);
                if(ratio){
                    if(key==='width')block.config.height=Math.max(1,Math.round(dimension/ratio));
                    else block.config.width=Math.max(1,Math.round(dimension*ratio));
                }
            }
        }
        this.refreshSelectedCard();
        this.syncImagePropertyInputs(block);
    }

    syncImageDimensionsToRatio(block){
        const ratio=this.resolveImageRatio(block);
        if(!ratio)return;
        const width=positiveInteger(block.config.width,5000);
        const height=positiveInteger(block.config.height,5000);
        if(width)block.config.height=Math.max(1,Math.round(width/ratio));
        else if(height)block.config.width=Math.max(1,Math.round(height*ratio));
    }

    resolveImageRatio(block){
        const configured=positiveNumber(block.config?.aspectRatio,0.02,50);
        if(configured)return configured;
        const card=this.root.querySelector(`[data-pb-block="${cssEscapeValue(block.id)}"]`);
        const image=card?.querySelector('.vo-preview-image-frame img');
        if(image?.naturalWidth>0&&image?.naturalHeight>0){
            block.config.aspectRatio=image.naturalWidth/image.naturalHeight;
            return block.config.aspectRatio;
        }
        const width=positiveInteger(block.config?.width,5000);
        const height=positiveInteger(block.config?.height,5000);
        if(width&&height){block.config.aspectRatio=width/height;return block.config.aspectRatio;}
        return null;
    }

    syncImagePropertyInputs(block){
        for(const key of ['width','height']){
            const input=this.root.querySelector(`[data-pb-config="${key}"]`);
            if(input)input.value=block.config?.[key]??'';
        }
    }

    updateVisibility(value){
        const b=this.selected();if(!b)return;
        b.visibility=value;
        const card=this.root.querySelector(`[data-pb-block="${cssEscapeValue(b.id)}"]`);
        const label=card?.querySelector('.vo-builder-visibility');
        if(label) label.textContent=visibilityLabel(value);
    }

    refreshSelectedCard(){
        const b=this.selected();if(!b)return;
        const card=this.root.querySelector(`[data-pb-block="${cssEscapeValue(b.id)}"]`);
        const preview=card?.querySelector('.vo-builder-block-preview');
        const name=card?.querySelector('.vo-builder-block-name');
        const visibility=card?.querySelector('.vo-builder-visibility');
        const width=card?.querySelector('.vo-builder-block-width');
        if(preview){
            preview.innerHTML=this.renderPreview(b);
            const uploadable=this.supportsDirectUpload(b);
            preview.classList.toggle('is-uploadable',uploadable);
            if(uploadable)preview.title='Двойной клик — выбрать файл';else preview.removeAttribute('title');
        }
        if(name) name.textContent=this.blockLabel(b);
        if(visibility) visibility.textContent=visibilityLabel(b.visibility);
        if(width) width.textContent=widthLabel(b.span);
        if(card){
            card.classList.remove('valign-top','valign-center','valign-bottom');
            card.classList.add(verticalAlignmentClass(b.config?.verticalAlignment,'TOP'));
        }
    }

    flashLimit(type){const item=this.root.querySelector(`[data-pb-palette="${type}"]`);item?.classList.add('is-limit-flash');setTimeout(()=>item?.classList.remove('is-limit-flash'),380);}

    supportsDirectUpload(block){
        return block?.type==='IMAGE'||(block?.type==='VIDEO'&&block.config?.source==='STATIC')||(block?.type==='FILE'&&block.config?.mode==='STATIC');
    }

    openAssetPicker(id,event){
        const block=this.template.blocks.find(item=>item.id===id);
        if(!this.supportsDirectUpload(block))return;
        event.preventDefault();
        event.stopPropagation();
        this.selectBlock(id);
        const input=this.root.querySelector('.vo-builder-sidebar [data-pb-upload][data-pb-slot="asset"]');
        input?.click();
    }

    clearAsset(slot){
        const b=this.selected();if(!b)return;
        b.config.assetUrl=null;b.config.assetName='';
        if(b.type==='IMAGE'){b.config.width=null;b.config.height=null;b.config.aspectRatio=null;b.config.viewportHeight=null;}
        this.refreshSelectedCard();
        this.renderSidebar();
    }

    async handleAssetUpload(input){
        const b=this.selected();
        const file=input.files?.[0];
        if(!b||!file||this.busy)return;
        const kind=input.dataset.pbUpload;
        const dimensions=kind==='IMAGE'?await readImageDimensions(file):null;
        this.busy=true;
        input.disabled=true;
        this.renderBusyState();
        try{
            const asset=await this.uploadAsset(kind,file);
            b.config.assetUrl=asset.url;
            b.config.assetName=asset.fileName;
            if(b.type==='IMAGE'&&dimensions){
                const fitted=fitImageDimensions(dimensions,5000);
                b.config.width=fitted.width;
                b.config.height=fitted.height;
                b.config.aspectRatio=dimensions.width/dimensions.height;
                if(b.config.keepAspectRatio===undefined)b.config.keepAspectRatio=true;
            }
            this.refreshSelectedCard();
            this.renderSidebar();
        }catch(e){
            alert(e.message||'Не удалось загрузить файл');
        }finally{
            this.busy=false;
            this.renderBusyState();
        }
    }

    async save(){
        if(this.busy)return;
        this.busy=true;
        this.renderBusyState();
        try{
            const saved=await this.saveTemplate(this.getTemplate());
            this.template=normalizeClientTemplate(saved);
            if(this.selectedId&&!this.template.blocks.some(b=>b.id===this.selectedId)){this.selectedId=null;this.panelMode='palette';}
            this.renderCanvasInPlace();
            this.renderSidebar();
            this.onSaved(saved);
        }catch(e){
            alert(e.message||'Не удалось сохранить шаблон');
        }finally{
            this.busy=false;
            this.renderBusyState();
        }
    }

    palettePointerDown(event,item){
        if(event.button!==0||item.classList.contains('is-disabled'))return;
        this.beginPointerDrag(event,{mode:'palette',type:item.dataset.pbPalette,source:item});
    }

    canvasPointerDown(event,item){
        if(event.button!==0||event.target.closest('button,input,select,textarea,a'))return;
        event.stopPropagation();
        this.beginPointerDrag(event,{mode:'canvas',id:item.dataset.pbBlock,source:item});
    }

    beginPointerDrag(event,meta){
        const start={x:event.clientX,y:event.clientY};
        let active=false;
        let floating=null;
        let currentTarget=null;
        let grabOffset={x:14,y:12};
        const sourceRect=meta.mode==='canvas'?meta.source.getBoundingClientRect():null;
        if(sourceRect)grabOffset={x:event.clientX-sourceRect.left,y:event.clientY-sourceRect.top};

        const move=e=>{
            if(!active&&Math.hypot(e.clientX-start.x,e.clientY-start.y)<5)return;
            if(!active){
                active=true;
                document.body.classList.add('vo-pb-dragging');
                if(meta.mode==='palette'){
                    floating=this.createPaletteFloating(meta.type);
                }else{
                    floating=meta.source.cloneNode(true);
                    floating.classList.add('vo-builder-floating','vo-builder-floating-block');
                    if(this.theme==='light')floating.classList.add('is-light');
                    floating.style.width=sourceRect.width+'px';
                    floating.style.height=sourceRect.height+'px';
                    meta.source.classList.add('vo-builder-source-dragging');
                    document.body.appendChild(floating);
                }
                this.ensureDragIndicator();
            }
            e.preventDefault();
            this.moveFloating(floating,e.clientX,e.clientY,grabOffset);
            currentTarget=this.resolveDropTarget(e.clientX,e.clientY,meta);
            this.paintDropTarget(currentTarget);
        };

        const up=e=>{
            document.removeEventListener('pointermove',move);
            document.removeEventListener('pointerup',up);
            document.removeEventListener('pointercancel',up);
            document.body.classList.remove('vo-pb-dragging');
            floating?.remove();
            meta.source?.classList.remove('vo-builder-source-dragging');
            this.clearDropTarget();
            if(active&&meta.mode==='palette')this.suppressPaletteClickUntil=Date.now()+300;
            if(active&&currentTarget)this.applyDrop(meta,currentTarget);
        };

        document.addEventListener('pointermove',move,{passive:false});
        document.addEventListener('pointerup',up,{passive:false});
        document.addEventListener('pointercancel',up,{passive:false});
    }

    resolveDropTarget(x,y,meta){
        const canvas=this.root.querySelector('[data-pb-canvas]');
        if(!canvas)return null;
        const canvasRect=canvas.getBoundingClientRect();
        if(x<canvasRect.left-30||x>canvasRect.right+30||y<canvasRect.top-30||y>canvasRect.bottom+30)return null;
        const rowEls=[...canvas.querySelectorAll('[data-pb-row]')];
        if(!rowEls.length)return {kind:'newRow',rowIndex:0};

        for(let i=0;i<rowEls.length;i++){
            const rowEl=rowEls[i];
            const rect=rowEl.getBoundingClientRect();
            const previous=i===0?canvasRect.top:rowEls[i-1].getBoundingClientRect().bottom;
            const gapTop=(previous+rect.top)/2;
            if(y<gapTop)return {kind:'newRow',rowIndex:i};
            if(y<=rect.bottom){
                const rowId=rowEl.dataset.pbRow;
                const sourceBlock=meta.mode==='canvas'?this.template.blocks.find(b=>b.id===meta.id):null;
                const sameRow=sourceBlock?.rowId===rowId;
                const blocks=[...rowEl.querySelectorAll('[data-pb-block]')].filter(el=>el.dataset.pbBlock!==meta.id);
                let insertIndex=blocks.length;
                let targetBlockId=null;
                for(let j=0;j<blocks.length;j++){
                    const br=blocks[j].getBoundingClientRect();
                    if(x>=br.left&&x<=br.right&&y>=br.top&&y<=br.bottom)targetBlockId=blocks[j].dataset.pbBlock;
                    if(x<br.left+br.width/2){insertIndex=j;break;}
                }
                if(sameRow)return {kind:'row',rowId,index:insertIndex,span:sourceBlock.span};
                const free=this.rowFreeSpan(rowId,null);
                if(free>=MIN_BLOCK_SPAN)return {kind:'row',rowId,index:insertIndex,span:free};
                if(meta.mode==='canvas'&&targetBlockId)return {kind:'swap',targetBlockId};
                return null;
            }
        }
        return {kind:'newRow',rowIndex:rowEls.length};
    }

    rowFreeSpan(rowId,excludeId){
        const row=this.layoutRows().find(item=>item.id===rowId);
        if(!row)return GRID_COLUMNS;
        const used=row.blocks.filter(block=>block.id!==excludeId).reduce((sum,block)=>sum+normalizedSpan(block.span),0);
        return Math.max(0,GRID_COLUMNS-used);
    }

    applyDrop(meta,target){
        const rows=this.layoutRows().map(row=>({id:row.id,blocks:[...row.blocks]}));
        let block;
        let sourceRowIndex=-1;
        let sourceBlockIndex=-1;
        if(meta.mode==='canvas'){
            for(let i=0;i<rows.length;i++){
                const j=rows[i].blocks.findIndex(item=>item.id===meta.id);
                if(j>=0){sourceRowIndex=i;sourceBlockIndex=j;block=rows[i].blocks[j];break;}
            }
            if(!block)return;
        }else{
            const metaInfo=BLOCKS.find(item=>item.type===meta.type);
            if(!metaInfo||(this.counts()[meta.type]||0)>=metaInfo.max)return this.flashLimit(meta.type);
            block=defaultBlock(meta.type,this.template.blocks);
        }

        if(meta.mode==='canvas'&&target.kind==='row'&&block.rowId===target.rowId){
            const row=rows[sourceRowIndex];
            row.blocks.splice(sourceBlockIndex,1);
            row.blocks.splice(Math.max(0,Math.min(target.index,row.blocks.length)),0,block);
            this.setRows(rows);
            this.afterDrop(block);
            return;
        }

        if(target.kind==='swap'&&meta.mode==='canvas'){
            let targetRowIndex=-1,targetIndex=-1,targetBlock=null;
            for(let i=0;i<rows.length;i++){
                const j=rows[i].blocks.findIndex(item=>item.id===target.targetBlockId);
                if(j>=0){targetRowIndex=i;targetIndex=j;targetBlock=rows[i].blocks[j];break;}
            }
            if(!targetBlock||targetBlock.id===block.id)return;
            const sourceSpan=block.span,targetSpan=targetBlock.span;
            rows[sourceRowIndex].blocks[sourceBlockIndex]=targetBlock;
            rows[targetRowIndex].blocks[targetIndex]=block;
            targetBlock.span=sourceSpan;
            block.span=targetSpan;
            this.setRows(rows);
            this.afterDrop(block);
            return;
        }

        let removedSourceRow=false;
        if(meta.mode==='canvas'){
            rows[sourceRowIndex].blocks.splice(sourceBlockIndex,1);
            if(!rows[sourceRowIndex].blocks.length){rows.splice(sourceRowIndex,1);removedSourceRow=true;}
        }

        if(target.kind==='newRow'){
            const row={id:uid('r'),blocks:[block]};
            block.span=GRID_COLUMNS;
            let rowIndex=target.rowIndex;
            if(removedSourceRow&&sourceRowIndex<rowIndex)rowIndex--;
            rows.splice(Math.max(0,Math.min(rowIndex,rows.length)),0,row);
        }else if(target.kind==='row'){
            let row=rows.find(item=>item.id===target.rowId);
            if(!row){row={id:target.rowId||uid('r'),blocks:[]};rows.push(row);}
            const free=Math.max(0,GRID_COLUMNS-row.blocks.reduce((sum,item)=>sum+normalizedSpan(item.span),0));
            if(meta.mode==='canvas'&&block.rowId===target.rowId){
                block.span=normalizedSpan(block.span);
            }else{
                if(free<MIN_BLOCK_SPAN)return;
                block.span=free;
            }
            row.blocks.splice(Math.max(0,Math.min(target.index,row.blocks.length)),0,block);
        }
        this.setRows(rows);
        this.afterDrop(block);
    }

    afterDrop(block){
        this.selectedId=block.id;
        this.panelMode='properties';
        this.renderCanvasInPlace();
        this.renderSidebar();
    }

    ensureDragIndicator(){
        if(this.dragIndicator)return;
        this.dragIndicator=document.createElement('div');
        this.dragIndicator.className='vo-builder-drop-indicator';
        document.body.appendChild(this.dragIndicator);
    }

    paintDropTarget(target){
        this.root.querySelectorAll('.is-drop-row,.is-swap-target').forEach(el=>el.classList.remove('is-drop-row','is-swap-target'));
        if(!this.dragIndicator)return;
        this.dragIndicator.hidden=!target;
        if(!target)return;
        const canvas=this.root.querySelector('[data-pb-canvas]');
        if(target.kind==='newRow'){
            const rows=[...canvas.querySelectorAll('[data-pb-row]')];
            const canvasRect=canvas.getBoundingClientRect();
            const y=target.rowIndex<rows.length?rows[target.rowIndex].getBoundingClientRect().top-5:canvasRect.bottom-8;
            this.dragIndicator.className='vo-builder-drop-indicator is-horizontal';
            Object.assign(this.dragIndicator.style,{left:(canvasRect.left+10)+'px',top:y+'px',width:Math.max(0,canvasRect.width-20)+'px',height:'3px'});
            return;
        }
        if(target.kind==='swap'){
            const el=this.root.querySelector(`[data-pb-block="${cssEscapeValue(target.targetBlockId)}"]`);
            el?.classList.add('is-swap-target');
            const r=el?.getBoundingClientRect();
            if(r){this.dragIndicator.className='vo-builder-drop-indicator is-box';Object.assign(this.dragIndicator.style,{left:r.left+'px',top:r.top+'px',width:r.width+'px',height:r.height+'px'});}
            return;
        }
        const row=this.root.querySelector(`[data-pb-row="${cssEscapeValue(target.rowId)}"]`);
        row?.classList.add('is-drop-row');
        const blocks=[...row.querySelectorAll('[data-pb-block]')].filter(el=>!el.classList.contains('vo-builder-source-dragging'));
        const rr=row.getBoundingClientRect();
        const x=target.index<blocks.length?blocks[target.index].getBoundingClientRect().left:rr.right-2;
        this.dragIndicator.className='vo-builder-drop-indicator is-vertical';
        Object.assign(this.dragIndicator.style,{left:(x-1)+'px',top:(rr.top+4)+'px',width:'3px',height:Math.max(0,rr.height-8)+'px'});
    }

    clearDropTarget(){
        this.root.querySelectorAll('.is-drop-row,.is-swap-target').forEach(el=>el.classList.remove('is-drop-row','is-swap-target'));
        this.dragIndicator?.remove();
        this.dragIndicator=null;
    }

    createPaletteFloating(type){
        const meta=BLOCKS.find(x=>x.type===type);
        const el=document.createElement('div');
        el.className='vo-builder-floating vo-builder-floating-palette';
        el.innerHTML=`<span class="vo-palette-icon">${esc(meta.icon)}</span><b>${esc(meta.label)}</b>`;
        document.body.appendChild(el);
        return el;
    }

    moveFloating(el,x,y,offset){
        if(!el)return;
        el.style.left=(x-(offset?.x??14))+'px';
        el.style.top=(y-(offset?.y??12))+'px';
    }
}

function normalizeClientTemplate(template){
    const input=JSON.parse(JSON.stringify(template || {version:2,blocks:[]}));
    const source=Array.isArray(input.blocks)?input.blocks:[];
    const blocks=[];
    source.forEach(raw=>{
        if(!raw||!raw.type)return;
        if(String(raw.type).toUpperCase()==='HEADER'){
            blocks.push(...migrateLegacyHeader(raw));
            return;
        }
        raw.id=raw.id||uid('b');
        raw.visibility=['ALL','DESKTOP','MOBILE'].includes(String(raw.visibility||'ALL').toUpperCase())?String(raw.visibility||'ALL').toUpperCase():'ALL';
        raw.rowId=raw.rowId||uid('r');
        raw.span=normalizedSpan(raw.span);
        raw.config=raw.config||{};
        raw.config.verticalAlignment=verticalAlignmentValue(raw.config.verticalAlignment,'TOP');
        blocks.push(raw);
    });
    return {version:2,blocks};
}

function migrateLegacyHeader(header){
    const c=header.config||{};
    const rowId=uid('r');
    const out=[];
    if(c.logoUrl)out.push({id:uid('b'),type:'IMAGE',visibility:header.visibility||'ALL',rowId,span:3,config:{assetUrl:c.logoUrl,assetName:c.logoName||'',alt:c.logoName||'Логотип',href:'',radius:'NONE',alignment:'LEFT',verticalAlignment:'TOP',width:150,height:64,keepAspectRatio:true,aspectRatio:null,viewportHeight:null}});
    if(c.companyName)out.push({id:uid('b'),type:'TEXT',visibility:header.visibility||'ALL',rowId,span:out.length?6:9,config:{mode:'STATIC',style:'HEADING',text:c.companyName,label:'Название компании',placeholder:'',required:false,alignment:'CENTER',verticalAlignment:'TOP',fontFamily:'DEFAULT',fontSize:24,bold:true,italic:false,underline:false}});
    if(c.phoneText)out.push({id:uid('b'),type:'ICON_TEXT',visibility:header.visibility||'ALL',rowId,span:3,config:{icon:'PHONE',text:c.phoneText,href:c.phoneHref||'',iconColor:'#2f80ed',iconSize:22,textColor:'#344f5f',alignment:'RIGHT',verticalAlignment:'TOP',fontFamily:'DEFAULT',fontSize:14,bold:true,italic:false,underline:false}});
    if(!out.length)return [];
    const used=out.reduce((sum,b)=>sum+b.span,0);
    if(used>12)out[out.length-1].span=Math.max(3,out[out.length-1].span-(used-12));
    return out;
}

function visibilityLabel(v){return v==='DESKTOP'?'Desktop':v==='MOBILE'?'Mobile':'';}
function inputField(label,key,value,type='text',placeholder=''){return `<label class="vo-builder-field">${esc(label)}<input type="${type}" data-pb-config="${key}" value="${attr(value??'')}" ${placeholder?`placeholder="${attr(placeholder)}"`:''}></label>`;}
function numberField(label,key,value,min,max,placeholder=''){return `<label class="vo-builder-field">${esc(label)}<input type="number" min="${min}" max="${max}" step="1" data-pb-config="${key}" value="${attr(value??'')}" ${placeholder?`placeholder="${attr(placeholder)}"`:''}></label>`;}
function textareaField(label,key,value,rows=5){return `<label class="vo-builder-field">${esc(label)}<textarea rows="${rows}" data-pb-config="${key}">${esc(value??'')}</textarea></label>`;}
function checkboxField(label,key,value){return `<label class="vo-builder-check"><input type="checkbox" data-pb-config="${key}" ${bool(value)?'checked':''}><span>${esc(label)}</span></label>`;}
function uploadField(label,kind,name,slot){const accept=kind==='IMAGE'?'.png,.jpg,.jpeg,.webp':kind==='VIDEO'?'.mp4,.webm':'.pdf,.doc,.docx,.xls,.xlsx,.ppt,.pptx,.txt,.rtf,.csv,.odt,.ods,.odp';return `<div class="vo-builder-field">${esc(label)}<div class="vo-builder-upload-row"><label class="vo-builder-upload"><input type="file" data-pb-upload="${kind}" data-pb-slot="${slot}" accept="${accept}"><span>${name?esc(name):'Выбрать файл'}</span></label>${name?`<button type="button" class="vo-builder-clear-asset" data-pb-clear-asset="${slot}" title="Убрать файл">✕</button>`:''}</div></div>`;}
function widthField(block,maxSpan){const current=normalizedSpan(block.span);const options=[...WIDTH_PRESETS];if(!options.includes(current))options.push(current);options.sort((a,b)=>b-a);return `<label class="vo-builder-field">Ширина блока<select data-pb-layout-span>${options.map(span=>`<option value="${span}" ${current===span?'selected':''} ${span>maxSpan?'disabled':''}>${widthLabel(span)}${WIDTH_PRESETS.includes(span)?'':' · свободное место'}</option>`).join('')}</select><small class="vo-builder-field-help">В строке доступно до ${widthLabel(maxSpan)}</small></label>`;}
function alignmentField(value,fallback){const current=alignmentValue(value,fallback);return `<div class="vo-builder-field"><span class="vo-builder-field-label">Выравнивание</span><div class="vo-builder-align-buttons" role="group" aria-label="Выравнивание"><button type="button" class="${current==='LEFT'?'is-active':''}" data-pb-choice-config="alignment" data-pb-value="LEFT" title="По левому краю" aria-label="По левому краю">≡</button><button type="button" class="${current==='CENTER'?'is-active':''}" data-pb-choice-config="alignment" data-pb-value="CENTER" title="По центру" aria-label="По центру">≡</button><button type="button" class="${current==='RIGHT'?'is-active':''}" data-pb-choice-config="alignment" data-pb-value="RIGHT" title="По правому краю" aria-label="По правому краю">≡</button></div></div>`;}
function verticalAlignmentField(value,fallback='TOP'){const current=verticalAlignmentValue(value,fallback);return `<div class="vo-builder-field"><span class="vo-builder-field-label">Положение по вертикали</span><div class="vo-builder-vertical-align-buttons" role="group" aria-label="Положение по вертикали"><button type="button" class="${current==='TOP'?'is-active':''}" data-pb-choice-config="verticalAlignment" data-pb-value="TOP" title="По верхнему краю" aria-label="По верхнему краю">↥</button><button type="button" class="${current==='CENTER'?'is-active':''}" data-pb-choice-config="verticalAlignment" data-pb-value="CENTER" title="По центру по вертикали" aria-label="По центру по вертикали">↕</button><button type="button" class="${current==='BOTTOM'?'is-active':''}" data-pb-choice-config="verticalAlignment" data-pb-value="BOTTOM" title="По нижнему краю" aria-label="По нижнему краю">↧</button></div></div>`;}
function typographyFields(c){const family=String(c.fontFamily||'DEFAULT').toUpperCase();return `<label class="vo-builder-field">Шрифт<select data-pb-config="fontFamily">${FONT_OPTIONS.map(([key,label])=>`<option value="${key}" ${family===key?'selected':''}>${esc(label)}</option>`).join('')}</select></label><div class="vo-builder-field"><span class="vo-builder-field-label">Начертание</span><div class="vo-builder-text-tools" role="group" aria-label="Начертание"><button type="button" class="${bool(c.bold)?'is-active':''}" data-pb-toggle-config="bold" title="Жирный"><b>B</b></button><button type="button" class="${bool(c.italic)?'is-active':''}" data-pb-toggle-config="italic" title="Курсив"><i>I</i></button><button type="button" class="${bool(c.underline)?'is-active':''}" data-pb-toggle-config="underline" title="Подчёркнутый"><u>U</u></button></div></div>${numberField('Размер шрифта, px','fontSize',c.fontSize,8,120,'Авто')}`;}
function imageSizeFields(c){return `<div class="vo-builder-field"><span class="vo-builder-field-label">Размер изображения</span><div class="vo-builder-dimension-row">${numberField('Ширина, px','width',c.width,1,5000,'Авто')}${numberField('Высота, px','height',c.height,1,5000,'Авто')}</div></div>${checkboxField('Соблюдать пропорции','keepAspectRatio',c.keepAspectRatio??true)}`;}
function alignmentValue(value,fallback='LEFT'){const normalized=String(value||fallback).toUpperCase();return ['LEFT','CENTER','RIGHT'].includes(normalized)?normalized:fallback;}
function alignmentClass(value,fallback='LEFT'){return 'align-'+alignmentValue(value,fallback).toLowerCase();}
function verticalAlignmentValue(value,fallback='TOP'){const normalized=String(value||fallback).toUpperCase();return ['TOP','CENTER','BOTTOM'].includes(normalized)?normalized:fallback;}
function verticalAlignmentClass(value,fallback='TOP'){return 'valign-'+verticalAlignmentValue(value,fallback).toLowerCase();}
function textInlineStyle(c,fallbackAlignment='LEFT'){const parts=[];const family=FONT_STACKS[String(c.fontFamily||'DEFAULT').toUpperCase()];if(family)parts.push(`font-family:${family}`);const size=positiveInteger(c.fontSize,120);if(size&&size>=8)parts.push(`font-size:${size}px`);parts.push(`font-weight:${bool(c.bold)?700:400}`);parts.push(`font-style:${bool(c.italic)?'italic':'normal'}`);parts.push(`text-decoration:${bool(c.underline)?'underline':'none'}`);parts.push(`text-align:${alignmentValue(c.alignment,fallbackAlignment).toLowerCase()}`);return parts.join(';');}
function imageFrameStyle(c){const width=positiveInteger(c.width,5000);const viewport=positiveInteger(c.viewportHeight,3000);const height=positiveInteger(c.height,5000);const keep=bool(c.keepAspectRatio??true);const parts=[`width:${width?width+'px':'100%'}`,'max-width:100%','position:relative','overflow:hidden'];if(viewport)parts.push(`height:${viewport}px`);else if(keep&&width&&height)parts.push(`aspect-ratio:${width}/${height}`);else if(!keep&&height)parts.push(`height:${height}px`);return parts.join(';');}
function imageContentStyle(c){const viewport=positiveInteger(c.viewportHeight,3000);const height=positiveInteger(c.height,5000);const keep=bool(c.keepAspectRatio??true);if(viewport)return keep?'position:absolute;left:50%;top:50%;transform:translate(-50%,-50%);width:100%;height:auto;max-width:none;max-height:none;object-fit:contain':'position:absolute;left:50%;top:50%;transform:translate(-50%,-50%);width:100%;height:'+(height?height+'px':'auto')+';max-width:none;max-height:none;object-fit:fill';return keep||!height?'width:100%;height:auto;object-fit:contain':'width:100%;height:100%;object-fit:fill';}
function positiveInteger(value,max){if(value===null||value===undefined||value==='')return null;const number=Math.round(Number(value));return Number.isFinite(number)&&number>0&&number<=max?number:null;}
function positiveNumber(value,min,max){const number=Number(value);return Number.isFinite(number)&&number>=min&&number<=max?number:null;}
function normalizedSpan(value){const n=Math.round(Number(value));return Number.isFinite(n)&&n>=MIN_BLOCK_SPAN&&n<=GRID_COLUMNS?n:GRID_COLUMNS;}
function widthLabel(span){const n=normalizedSpan(span);const exact={12:'100%',9:'75%',8:'67%',6:'50%',4:'33%',3:'25%'}[n];return exact||`${Math.round(n/GRID_COLUMNS*100)}%`;}
function codeSummary(code){const text=String(code||'').trim().replace(/\s+/g,' ');return text?text.slice(0,90)+(text.length>90?'…':''):'Код пока не задан';}
function readImageDimensions(file){return new Promise(resolve=>{const url=URL.createObjectURL(file);const image=new Image();const finish=value=>{URL.revokeObjectURL(url);resolve(value);};image.onload=()=>finish(image.naturalWidth>0&&image.naturalHeight>0?{width:image.naturalWidth,height:image.naturalHeight}:null);image.onerror=()=>finish(null);image.src=url;});}
function fitImageDimensions(dimensions,max){const scale=Math.min(1,max/dimensions.width,max/dimensions.height);return{width:Math.max(1,Math.round(dimensions.width*scale)),height:Math.max(1,Math.round(dimensions.height*scale))};}
function defaultBlock(type,existing){const base={id:uid('b'),type,visibility:'ALL',rowId:uid('r'),span:GRID_COLUMNS,config:{}};switch(type){case'VIDEO':base.config={source:existing.some(b=>b.type==='VIDEO'&&b.config?.source==='MAIN')?'STATIC':'MAIN',title:'',assetUrl:null,assetName:''};break;case'TEXT':base.config={mode:'STATIC',style:'PARAGRAPH',text:'Новый текстовый блок',label:'Текст',placeholder:'',required:false,alignment:'LEFT',fontFamily:'DEFAULT',fontSize:null,bold:false,italic:false,underline:false};break;case'IMAGE':base.config={assetUrl:null,assetName:'',alt:'',href:'',radius:'LARGE',alignment:'CENTER',width:null,height:null,keepAspectRatio:true,aspectRatio:null,viewportHeight:null};break;case'FILE':base.config={mode:'STATIC',assetUrl:null,assetName:'',label:'Скачать файл',required:false,alignment:'LEFT'};break;case'BUTTON':base.config={text:'Подробнее',href:'',color:'#2f80ed',shape:'PILL',depth:'FLAT',shadow:'NONE',hoverAnimation:'LIFT',clickAnimation:'PRESS',newTab:false,alignment:'CENTER',fontFamily:'DEFAULT',fontSize:null,bold:true,italic:false,underline:false};break;case'ICON_TEXT':base.config={icon:'PHONE',text:'Телефон или подпись',href:'',iconColor:'#2f80ed',iconSize:24,textColor:'#344f5f',alignment:'LEFT',fontFamily:'DEFAULT',fontSize:14,bold:false,italic:false,underline:false};break;case'DIVIDER':base.config={style:'SOLID'};break;case'EMBED':base.config={codeType:'HTML',code:''};break;}base.config.verticalAlignment='TOP';return base;}
function iconSvg(name){const icon=String(name||'PHONE').toUpperCase();const paths={PHONE:'<path d="M6.6 10.8c1.5 3 3.6 5.1 6.6 6.6l2.2-2.2c.3-.3.7-.4 1.1-.3 1.2.4 2.5.6 3.8.6.6 0 1 .4 1 1V20c0 .6-.4 1-1 1C10.8 21 3 13.2 3 3.7c0-.6.4-1 1-1h3.5c.6 0 1 .4 1 1 0 1.3.2 2.6.6 3.8.1.4 0 .8-.3 1.1l-2.2 2.2z"/>',MAIL:'<path d="M3 5h18v14H3z" fill="none"/><path d="m4 6 8 7 8-7" fill="none"/>',LOCATION:'<path d="M12 22s7-6 7-13a7 7 0 1 0-14 0c0 7 7 13 7 13z" fill="none"/><circle cx="12" cy="9" r="2.5" fill="none"/>',LINK:'<path d="M10 13a5 5 0 0 0 7.1 0l2-2a5 5 0 0 0-7.1-7.1l-1.1 1.1M14 11a5 5 0 0 0-7.1 0l-2 2A5 5 0 0 0 12 20.1l1.1-1.1" fill="none"/>',MESSAGE:'<path d="M4 4h16v12H8l-4 4z" fill="none"/>',CLOCK:'<circle cx="12" cy="12" r="9" fill="none"/><path d="M12 7v5l3 2" fill="none"/>',USER:'<circle cx="12" cy="8" r="4" fill="none"/><path d="M4 21a8 8 0 0 1 16 0" fill="none"/>',CHECK:'<path d="m4 12 5 5L20 6" fill="none"/>',INFO:'<circle cx="12" cy="12" r="9" fill="none"/><path d="M12 11v6M12 7h.01" fill="none"/>'};return `<svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">${paths[icon]||paths.PHONE}</svg>`;}
function cssEscapeValue(value){if(globalThis.CSS?.escape)return CSS.escape(String(value));return String(value).replace(/[^A-Za-z0-9_-]/g,'\\$&');}

window.VideoOfferPageBuilder = VideoOfferPageBuilder;
})();
