(() => {
'use strict';

const BLOCKS = [
    {type:'HEADER', label:'Верхний блок', icon:'▤', max:1, description:'Логотип · название · телефон'},
    {type:'VIDEO', label:'Видео', icon:'▶', max:3, description:'Основной оффер или отдельное видео'},
    {type:'TEXT', label:'Текст', icon:'T', max:3, description:'Статический или заполняемый менеджером'},
    {type:'IMAGE', label:'Изображение', icon:'▧', max:3, description:'Баннер, фото или иллюстрация'},
    {type:'FILE', label:'Файл', icon:'⇩', max:3, description:'Статический или файл менеджера'},
    {type:'BUTTON', label:'Кнопка', icon:'↗', max:3, description:'Ссылка, звонок или мессенджер'},
    {type:'DIVIDER', label:'Разделитель', icon:'—', max:3, description:'Визуальное разделение блоков'}
];

const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const attr = esc;
const bool = value => value === true || String(value).toLowerCase() === 'true';
const uid = () => globalThis.crypto?.randomUUID?.().replaceAll('-','') || ('b'+Date.now().toString(36)+Math.random().toString(36).slice(2));

class VideoOfferPageBuilder {
    constructor(options) {
        this.root = options.root;
        this.theme = options.theme || 'dark';
        this.uploadAsset = options.uploadAsset;
        this.saveTemplate = options.saveTemplate;
        this.template = cloneTemplate(options.template);
        this.selectedId = null;
        this.panelMode = 'palette';
        this.busy = false;
        this.drag = null;
        this.suppressPaletteClickUntil = 0;
        this.onSaved = options.onSaved || (()=>{});
        this.render();
    }

    setTemplate(template) {
        this.template = cloneTemplate(template);
        this.selectedId = null;
        this.panelMode = 'palette';
        this.render();
    }

    getTemplate() { return JSON.parse(JSON.stringify(this.template)); }

    render() {
        if (!this.root) return;
        this.root.innerHTML = `
          <div class="vo-page-builder ${this.theme === 'light' ? 'is-light' : 'is-dark'}">
            <aside class="vo-builder-sidebar">
              <div class="vo-builder-sidebar-head">
                <div><span class="vo-builder-eyebrow">КОНСТРУКТОР</span><b>${this.panelMode === 'properties' ? 'Параметры блока' : 'Элементы'}</b></div>
                ${this.panelMode === 'properties' ? '<button type="button" class="vo-builder-back" data-pb-back>← Элементы</button>' : ''}
              </div>
              <div class="vo-builder-sidebar-body">${this.panelMode === 'properties' ? this.renderProperties() : this.renderPalette()}</div>
            </aside>
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

    renderPalette() {
        const counts = this.counts();
        return `<div class="vo-builder-palette">${BLOCKS.map(meta => {
            const count = counts[meta.type] || 0;
            const disabled = count >= meta.max;
            return `<button type="button" class="vo-palette-item ${disabled ? 'is-disabled' : ''}" data-pb-palette="${meta.type}" ${disabled ? 'aria-disabled="true"' : ''}>
                <span class="vo-palette-icon">${meta.icon}</span><span class="vo-palette-copy"><b>${meta.label}</b><small>${meta.description}</small></span><span class="vo-palette-count">${count}/${meta.max}</span>
            </button>`;
        }).join('')}</div>`;
    }

    renderCanvas() {
        if (!this.template.blocks.length) return '<div class="vo-builder-empty">Перетащите элемент сюда</div>';
        return this.template.blocks.map(block => this.renderBlockCard(block)).join('');
    }

    renderBlockCard(block) {
        const selected = block.id === this.selectedId;
        const locked = block.type === 'HEADER' || (block.type === 'VIDEO' && block.config?.source === 'MAIN');
        return `<article class="vo-builder-block ${selected ? 'is-selected' : ''}" data-pb-block="${attr(block.id)}">
            <div class="vo-builder-block-chrome">
              <span class="vo-builder-drag-handle" title="Перетащить">⋮⋮</span>
              <span class="vo-builder-block-name">${this.blockLabel(block)}</span>
              <span class="vo-builder-visibility">${visibilityLabel(block.visibility)}</span>
              <button type="button" class="vo-builder-block-delete" data-pb-delete="${attr(block.id)}" ${locked ? 'disabled title="Обязательный блок"' : 'title="Удалить"'}>✕</button>
            </div>
            <div class="vo-builder-block-preview">${this.renderPreview(block)}</div>
        </article>`;
    }

    renderPreview(block) {
        const c = block.config || {};
        switch (block.type) {
            case 'HEADER': return `<div class="vo-preview-header"><div class="vo-preview-logo">${c.logoUrl ? `<img src="${attr(c.logoUrl)}" alt="">` : '<span>LOGO</span>'}</div><div class="vo-preview-company">${esc(c.companyName || 'Название компании')}</div><div class="vo-preview-phone">☎ ${esc(c.phoneText || 'Телефон')}</div></div>`;
            case 'VIDEO': return `<div class="vo-preview-video"><span class="vo-preview-play">▶</span><div><b>${c.source === 'MAIN' ? 'Видеооффер' : esc(c.assetName || 'Дополнительное видео')}</b>${c.title ? `<small>${esc(c.title)}</small>` : ''}</div></div>`;
            case 'TEXT': {
                const sample = c.mode === 'MANAGER' ? `[Менеджер заполняет: ${c.label || 'Текст'}]` : (c.text || 'Текстовый блок');
                const tag = c.style === 'HEADING' ? 'h3' : 'p';
                return `<${tag} class="vo-preview-text ${c.style === 'NOTE' ? 'is-note' : ''}">${esc(sample)}</${tag}>`;
            }
            case 'IMAGE': return c.assetUrl ? `<div class="vo-preview-image"><img class="radius-${String(c.radius || 'LARGE').toLowerCase()}" src="${attr(c.assetUrl)}" alt=""></div>` : '<div class="vo-preview-placeholder">Изображение</div>';
            case 'FILE': return `<div class="vo-preview-file"><span>⇩</span><div><b>${esc(c.label || 'Скачать файл')}</b><small>${c.mode === 'MANAGER' ? (c.required ? 'Менеджер · обязательно' : 'Менеджер · необязательно') : esc(c.assetName || 'Статический файл')}</small></div></div>`;
            case 'BUTTON': return `<div class="vo-preview-button-wrap"><span class="vo-preview-button shape-${String(c.shape || 'PILL').toLowerCase()}" style="--pb-button:${attr(c.color || '#2f80ed')}">${esc(c.text || 'Подробнее')}</span></div>`;
            case 'DIVIDER': return `<div class="vo-preview-divider style-${String(c.style || 'SOLID').toLowerCase()}"></div>`;
            default: return '';
        }
    }

    renderProperties() {
        const block = this.selected();
        if (!block) { this.panelMode='palette'; return this.renderPalette(); }
        const c = block.config || {};
        const common = `<label class="vo-builder-field">Видимость<select data-pb-prop="visibility"><option value="ALL" ${block.visibility==='ALL'?'selected':''}>Компьютер и телефон</option><option value="DESKTOP" ${block.visibility==='DESKTOP'?'selected':''}>Только компьютер</option><option value="MOBILE" ${block.visibility==='MOBILE'?'selected':''}>Только телефон</option></select></label>`;
        let specific='';
        if (block.type === 'HEADER') specific = `
            ${uploadField('Логотип', 'IMAGE', c.logoName, 'logo')}
            ${inputField('Название компании','companyName',c.companyName,'text')}
            ${inputField('Телефон / подпись','phoneText',c.phoneText,'text')}
            ${inputField('Ссылка телефона','phoneHref',c.phoneHref,'text','tel:+79990000000')}`;
        if (block.type === 'VIDEO') specific = `
            <label class="vo-builder-field">Источник<select data-pb-config="source"><option value="MAIN" ${c.source==='MAIN'?'selected':''}>Основной видеооффер</option><option value="STATIC" ${c.source==='STATIC'?'selected':''}>Загруженное видео</option></select></label>
            ${inputField('Подпись','title',c.title,'text')}
            ${c.source === 'STATIC' ? uploadField('Видеофайл MP4 / WebM','VIDEO',c.assetName,'asset') : ''}`;
        if (block.type === 'TEXT') specific = `
            <label class="vo-builder-field">Источник текста<select data-pb-config="mode"><option value="STATIC" ${c.mode==='STATIC'?'selected':''}>Статический текст</option><option value="MANAGER" ${c.mode==='MANAGER'?'selected':''}>Редактирует менеджер</option></select></label>
            <label class="vo-builder-field">Вид текста<select data-pb-config="style"><option value="HEADING" ${c.style==='HEADING'?'selected':''}>Заголовок</option><option value="PARAGRAPH" ${c.style==='PARAGRAPH'?'selected':''}>Обычный текст</option><option value="NOTE" ${c.style==='NOTE'?'selected':''}>Выделенный текст</option></select></label>
            ${c.mode === 'STATIC' ? textareaField('Текст','text',c.text,6) : `${inputField('Название поля для менеджера','label',c.label,'text')}${inputField('Подсказка','placeholder',c.placeholder,'text')}${checkboxField('Обязательное поле','required',c.required)}`}`;
        if (block.type === 'IMAGE') specific = `${uploadField('Изображение','IMAGE',c.assetName,'asset')}${inputField('Описание изображения','alt',c.alt,'text')}${inputField('Ссылка при клике','href',c.href,'text','https://...')}<label class="vo-builder-field">Скругление<select data-pb-config="radius"><option value="NONE" ${c.radius==='NONE'?'selected':''}>Без скругления</option><option value="SMALL" ${c.radius==='SMALL'?'selected':''}>Небольшое</option><option value="LARGE" ${c.radius==='LARGE'?'selected':''}>Большое</option></select></label>`;
        if (block.type === 'FILE') specific = `
            <label class="vo-builder-field">Источник файла<select data-pb-config="mode"><option value="STATIC" ${c.mode==='STATIC'?'selected':''}>Загружает администратор</option><option value="MANAGER" ${c.mode==='MANAGER'?'selected':''}>Загружает менеджер</option></select></label>
            ${inputField('Подпись','label',c.label,'text')}
            ${c.mode === 'STATIC' ? uploadField('Документ','FILE',c.assetName,'asset') : checkboxField('Файл обязателен при создании оффера','required',c.required)}`;
        if (block.type === 'BUTTON') specific = `${inputField('Текст кнопки','text',c.text,'text')}${inputField('Ссылка','href',c.href,'text','https://, tel:, mailto:')}${inputField('Цвет','color',c.color || '#2f80ed','color')}<label class="vo-builder-field">Форма<select data-pb-config="shape"><option value="SQUARE" ${c.shape==='SQUARE'?'selected':''}>Прямоугольная</option><option value="ROUNDED" ${c.shape==='ROUNDED'?'selected':''}>Скруглённая</option><option value="PILL" ${c.shape==='PILL'?'selected':''}>Капсула</option></select></label>${checkboxField('Открывать в новой вкладке','newTab',c.newTab)}`;
        if (block.type === 'DIVIDER') specific = `<label class="vo-builder-field">Линия<select data-pb-config="style"><option value="SOLID" ${c.style==='SOLID'?'selected':''}>Сплошная</option><option value="DASHED" ${c.style==='DASHED'?'selected':''}>Штриховая</option><option value="DOTTED" ${c.style==='DOTTED'?'selected':''}>Точечная</option></select></label>`;
        return `<div class="vo-builder-properties"><div class="vo-builder-selected-title"><span>${BLOCKS.find(x=>x.type===block.type)?.icon || '□'}</span><div><b>${this.blockLabel(block)}</b></div></div>${common}${specific}</div>`;
    }

    bind() {
        this.root.querySelector('[data-pb-back]')?.addEventListener('click',()=>{this.panelMode='palette';this.render();});
        this.root.querySelector('[data-pb-save]')?.addEventListener('click',()=>this.save());
        this.root.querySelectorAll('[data-pb-palette]').forEach(item => {
            item.addEventListener('click', e => {
                if (Date.now() < this.suppressPaletteClickUntil) return;
                if (!item.classList.contains('is-disabled')) this.addBlock(item.dataset.pbPalette);
            });
            item.addEventListener('pointerdown', e => this.palettePointerDown(e,item));
        });
        this.root.querySelectorAll('[data-pb-block]').forEach(item => {
            item.addEventListener('click',e=>{ if(e.target.closest('[data-pb-delete]')) return; this.selectBlock(item.dataset.pbBlock); });
            item.querySelector('.vo-builder-drag-handle')?.addEventListener('pointerdown',e=>this.canvasPointerDown(e,item));
        });
        this.root.querySelectorAll('[data-pb-delete]').forEach(btn=>btn.addEventListener('click',e=>{e.stopPropagation();if(!btn.disabled)this.deleteBlock(btn.dataset.pbDelete);}));
        this.root.querySelectorAll('[data-pb-config]').forEach(input=>{
            const key = input.dataset.pbConfig;
            const structural = key === 'source' || key === 'mode';
            const eventName = (input.tagName === 'SELECT' || input.type === 'checkbox') ? 'change' : 'input';
            input.addEventListener(eventName,()=>this.updateConfig(
                key,
                input.type==='checkbox'?input.checked:input.value,
                structural));
        });
        this.root.querySelector('[data-pb-prop="visibility"]')?.addEventListener('change',e=>this.updateVisibility(e.target.value));
        this.root.querySelectorAll('[data-pb-upload]').forEach(input=>input.addEventListener('change',e=>this.handleAssetUpload(input)));
        this.root.querySelectorAll('[data-pb-clear-asset]').forEach(btn=>btn.addEventListener('click',()=>this.clearAsset(btn.dataset.pbClearAsset)));
    }

    counts(){ const out={}; for(const b of this.template.blocks) out[b.type]=(out[b.type]||0)+1; return out; }
    selected(){ return this.template.blocks.find(b=>b.id===this.selectedId) || null; }
    blockLabel(block){ const label=BLOCKS.find(x=>x.type===block.type)?.label || block.type; if(block.type==='VIDEO'&&block.config?.source==='MAIN')return label+' · основной'; return label; }

    addBlock(type,index=null) {
        const meta=BLOCKS.find(x=>x.type===type); if(!meta)return;
        if((this.counts()[type]||0)>=meta.max)return this.flashLimit(type);
        const block=defaultBlock(type,this.template.blocks);
        if(index===null||index<0||index>this.template.blocks.length)this.template.blocks.push(block);else this.template.blocks.splice(index,0,block);
        this.selectedId=block.id;this.panelMode='properties';this.render();
    }
    deleteBlock(id){ const i=this.template.blocks.findIndex(b=>b.id===id); if(i<0)return;this.template.blocks.splice(i,1);if(this.selectedId===id){this.selectedId=null;this.panelMode='palette';}this.render(); }
    selectBlock(id){this.selectedId=id;this.panelMode='properties';this.render();}
    updateConfig(key,value,rerender=true){
        const b=this.selected();if(!b)return;
        b.config=b.config||{};
        if(key==='source'&&b.type==='VIDEO'&&value==='MAIN'){
            this.template.blocks.filter(x=>x.type==='VIDEO'&&x.id!==b.id&&x.config?.source==='MAIN').forEach(x=>{x.config.source='STATIC';x.config.assetUrl=null;x.config.assetName='';});
        }
        b.config[key]=value;
        if(key==='mode'&&b.type==='TEXT'&&value==='STATIC')delete b.config.required;
        if(rerender){this.renderPreservingStageScroll();return;}
        this.refreshSelectedCard();
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
        if(preview) preview.innerHTML=this.renderPreview(b);
        if(name) name.textContent=this.blockLabel(b);
        if(visibility) visibility.textContent=visibilityLabel(b.visibility);
    }
    renderPreservingStageScroll(){
        const stage=this.root.querySelector('.vo-builder-stage');
        const sidebar=this.root.querySelector('.vo-builder-sidebar-body');
        const stageTop=stage?.scrollTop||0;
        const sidebarTop=sidebar?.scrollTop||0;
        this.render();
        const nextStage=this.root.querySelector('.vo-builder-stage');
        const nextSidebar=this.root.querySelector('.vo-builder-sidebar-body');
        if(nextStage) nextStage.scrollTop=stageTop;
        if(nextSidebar) nextSidebar.scrollTop=sidebarTop;
    }
    flashLimit(type){const item=this.root.querySelector(`[data-pb-palette="${type}"]`);item?.classList.add('is-limit-flash');setTimeout(()=>item?.classList.remove('is-limit-flash'),380);}

    clearAsset(slot){const b=this.selected();if(!b)return;if(b.type==='HEADER'&&slot==='logo'){b.config.logoUrl=null;b.config.logoName='';}else{b.config.assetUrl=null;b.config.assetName='';}this.render();}
    async handleAssetUpload(input){const b=this.selected();if(!b||!input.files?.[0]||this.busy)return;const kind=input.dataset.pbUpload;this.busy=true;input.disabled=true;try{const asset=await this.uploadAsset(kind,input.files[0]);if(b.type==='HEADER'&&input.dataset.pbSlot==='logo'){b.config.logoUrl=asset.url;b.config.logoName=asset.fileName;}else{b.config.assetUrl=asset.url;b.config.assetName=asset.fileName;}this.render();}catch(e){alert(e.message||'Не удалось загрузить файл');this.busy=false;this.render();return;}this.busy=false;this.render();}
    async save(){if(this.busy)return;this.busy=true;this.render();try{const saved=await this.saveTemplate(this.getTemplate());this.template=cloneTemplate(saved);this.onSaved(saved);this.busy=false;this.render();}catch(e){this.busy=false;this.render();alert(e.message||'Не удалось сохранить шаблон');}}

    palettePointerDown(event,item){if(event.button!==0||item.classList.contains('is-disabled'))return;this.beginPointerDrag(event,{mode:'palette',type:item.dataset.pbPalette,source:item});}
    canvasPointerDown(event,item){if(event.button!==0)return;event.stopPropagation();this.beginPointerDrag(event,{mode:'canvas',id:item.dataset.pbBlock,source:item});}
    beginPointerDrag(event,meta){const start={x:event.clientX,y:event.clientY};let active=false,floating=null,placeholder=null;const move=e=>{if(!active&&Math.hypot(e.clientX-start.x,e.clientY-start.y)<5)return;if(!active){active=true;document.body.classList.add('vo-pb-dragging');if(meta.mode==='palette'){floating=this.createPaletteFloating(meta.type);placeholder=document.createElement('div');placeholder.className='vo-builder-drop-placeholder';}else{floating=meta.source.cloneNode(true);floating.classList.add('vo-builder-floating');const r=meta.source.getBoundingClientRect();floating.style.width=r.width+'px';placeholder=document.createElement('div');placeholder.className='vo-builder-drop-placeholder';placeholder.style.height=r.height+'px';meta.source.classList.add('vo-builder-source-dragging');document.body.appendChild(floating);} }e.preventDefault();this.moveFloating(floating,e.clientX,e.clientY);const canvas=this.root.querySelector('[data-pb-canvas]');if(!canvas)return;const r=canvas.getBoundingClientRect();if(e.clientX<r.left-40||e.clientX>r.right+40||e.clientY<r.top-50||e.clientY>r.bottom+50){placeholder?.remove();return;}const blocks=[...canvas.querySelectorAll('[data-pb-block]')].filter(el=>el!==meta.source);let before=null;for(const el of blocks){const br=el.getBoundingClientRect();if(e.clientY<br.top+br.height/2){before=el;break;}}canvas.insertBefore(placeholder,before);};const up=e=>{document.removeEventListener('pointermove',move);document.removeEventListener('pointerup',up);document.removeEventListener('pointercancel',up);document.body.classList.remove('vo-pb-dragging');floating?.remove();meta.source?.classList.remove('vo-builder-source-dragging');if(active&&meta.mode==='palette')this.suppressPaletteClickUntil=Date.now()+300;if(!active||!placeholder?.parentElement){placeholder?.remove();return;}const canvas=placeholder.parentElement;const index=[...canvas.children].indexOf(placeholder);placeholder.remove();if(meta.mode==='palette'){this.addBlock(meta.type,index);}else{const old=this.template.blocks.findIndex(b=>b.id===meta.id);if(old>=0){const [block]=this.template.blocks.splice(old,1);let target=index;if(old<index)target--;this.template.blocks.splice(Math.max(0,target),0,block);this.render();}}};document.addEventListener('pointermove',move,{passive:false});document.addEventListener('pointerup',up,{passive:false});document.addEventListener('pointercancel',up,{passive:false});}
    createPaletteFloating(type){const meta=BLOCKS.find(x=>x.type===type);const el=document.createElement('div');el.className='vo-builder-floating vo-builder-floating-palette';el.innerHTML=`<span class="vo-palette-icon">${meta.icon}</span><b>${meta.label}</b>`;document.body.appendChild(el);return el;}
    moveFloating(el,x,y){if(!el)return;el.style.left=(x+14)+'px';el.style.top=(y+12)+'px';}
}

function cloneTemplate(template){return JSON.parse(JSON.stringify(template || {version:1,blocks:[]}));}
function visibilityLabel(v){return v==='DESKTOP'?'Desktop':v==='MOBILE'?'Mobile':'Все';}
function inputField(label,key,value,type='text',placeholder=''){return `<label class="vo-builder-field">${esc(label)}<input type="${type}" data-pb-config="${key}" value="${attr(value??'')}" ${placeholder?`placeholder="${attr(placeholder)}"`:''}></label>`;}
function textareaField(label,key,value,rows=5){return `<label class="vo-builder-field">${esc(label)}<textarea rows="${rows}" data-pb-config="${key}">${esc(value??'')}</textarea></label>`;}
function checkboxField(label,key,value){return `<label class="vo-builder-check"><input type="checkbox" data-pb-config="${key}" ${bool(value)?'checked':''}><span>${esc(label)}</span></label>`;}
function uploadField(label,kind,name,slot){const accept=kind==='IMAGE'?'.png,.jpg,.jpeg,.webp':kind==='VIDEO'?'.mp4,.webm':'.pdf,.doc,.docx,.xls,.xlsx,.ppt,.pptx,.txt,.rtf,.csv,.odt,.ods,.odp';return `<div class="vo-builder-field">${esc(label)}<div class="vo-builder-upload-row"><label class="vo-builder-upload"><input type="file" data-pb-upload="${kind}" data-pb-slot="${slot}" accept="${accept}"><span>${name?esc(name):'Выбрать файл'}</span></label>${name?`<button type="button" class="vo-builder-clear-asset" data-pb-clear-asset="${slot}" title="Убрать файл">✕</button>`:''}</div></div>`;}
function defaultBlock(type,existing){const id=uid();switch(type){case'HEADER':return{id,type,visibility:'ALL',config:{logoUrl:null,logoName:'',companyName:'Название компании',phoneText:'',phoneHref:''}};case'VIDEO':return{id,type,visibility:'ALL',config:{source:existing.some(b=>b.type==='VIDEO'&&b.config?.source==='MAIN')?'STATIC':'MAIN',title:'',assetUrl:null,assetName:''}};case'TEXT':return{id,type,visibility:'ALL',config:{mode:'STATIC',style:'PARAGRAPH',text:'Новый текстовый блок',label:'Текст',placeholder:'',required:false}};case'IMAGE':return{id,type,visibility:'ALL',config:{assetUrl:null,assetName:'',alt:'',href:'',radius:'LARGE'}};case'FILE':return{id,type,visibility:'ALL',config:{mode:'STATIC',assetUrl:null,assetName:'',label:'Скачать файл',required:false}};case'BUTTON':return{id,type,visibility:'ALL',config:{text:'Подробнее',href:'',color:'#2f80ed',shape:'PILL',newTab:false}};case'DIVIDER':return{id,type,visibility:'ALL',config:{style:'SOLID'}};default:return{id,type,visibility:'ALL',config:{}};}}

function cssEscapeValue(value){if(globalThis.CSS?.escape)return CSS.escape(String(value));return String(value).replace(/[^A-Za-z0-9_-]/g,'\\$&');}

window.VideoOfferPageBuilder = VideoOfferPageBuilder;
})();
