(function (root) {
  'use strict';
  const normalize = value => String(value).replace(/\s+/g, ' ').trim();
  const hasArabic = value => /[\u0621-\u064a]/.test(value);
  const escapeRegex = value => value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const getPath = (value, path) => path.replace(/\[(\d+)\]/g, '.$1').split('.').reduce((o,k) => o && o[k], value);

  // Translate only known, fully covered text. Unknown/edited prose stays intact.
  function makeTranslator(pairs) {
    const map = new Map(pairs.map(([ar,en]) => [normalize(ar), en]));
    const keys = [...map.keys()].filter(Boolean).sort((a,b) => b.length-a.length);
    const pattern = keys.length ? new RegExp(keys.map(escapeRegex).join('|'), 'gu') : null;
    return function translate(value) {
      const source = normalize(value);
      if (!source) return value;
      let result = map.get(source);
      if (result === undefined && pattern) {
        const tokens = [];
        let uncovered = source.replace(pattern, (match,offset) => {
          const before = source[offset-1] || '', after = source[offset+match.length] || '';
          if (hasArabic(before) || hasArabic(after)) return match;
          tokens.push(map.get(match));
          return '\u0001' + (tokens.length-1) + '\u0002';
        });
        if (hasArabic(uncovered)) return value;
        result = uncovered.replace(/\u0001(\d+)\u0002/g, (_,i) => tokens[+i]);
      }
      if (result === undefined) return value;
      return (value.match(/^\s*/)||[''])[0] + result + (value.match(/\s*$/)||[''])[0];
    };
  }
  if (typeof module === 'object' && module.exports) module.exports = {makeTranslator,getPath,normalize};
  if (!root.document) return;
  const document = root.document;
  const catalog = root.CT_EN;
  if (!catalog) return;
  const preferenceKey = 'ct_lang_v1';
  let language = 'ar', data, state, observer, scheduled = false, translate = value => value;
  const originals = new WeakMap();
  const reverseMedia = new Map(Object.entries(catalog.media).map(([ar,en]) => [en,ar]));
  const skip = 'script,style,textarea,input,[contenteditable],[data-no-translate]';
  // data-cap is canonical input to the image viewer, not visible text.
  const attrs = ['alt','title','aria-label','placeholder'];
  const homeFields = /^(title|duration|badge)$/;
  let controls;

  function rebuild() {
    data = root.DATA || data;
    const pairs = Object.entries(catalog.ui);
    for (const c of data.cases) {
      for (const item of catalog.cases[c.id] || []) {
        if ((c.id === state.caseId || homeFields.test(item.path)) && getPath(c,item.path) === item.ar) pairs.push([item.ar,item.en]);
      }
    }
    translate = makeTranslator(pairs);
  }
  function sourceValue(node,key,value) {
    let record = originals.get(node);
    if (!record) { record = {}; originals.set(node,record); }
    if (!record[key] || record[key].last !== value) record[key] = {source:value,last:value};
    return record[key];
  }
  function updateText(node) {
    if (!node.parentElement || node.parentElement.closest(skip)) return;
    const record = sourceValue(node,'text',node.nodeValue);
    const next = language === 'en' ? translate(record.source) : record.source;
    if (node.nodeValue !== next) node.nodeValue = next;
    record.last = next;
  }
  function updateAttribute(el,key,media) {
    if (!el.hasAttribute(key)) return;
    const record = sourceValue(el,key,el.getAttribute(key));
    const source = media ? (reverseMedia.get(record.source) || record.source) : record.source;
    const next = language === 'en' ? (media ? (catalog.media[source] || source) : translate(source)) : source;
    if (el.getAttribute(key) !== next) el.setAttribute(key,next);
    record.last = next;
  }
  function apply() {
    scheduled = false;
    if (!data) return;
    observer.disconnect();
    rebuild();
    document.documentElement.lang = language;
    document.documentElement.dir = language === 'en' ? 'ltr' : 'rtl';
    const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
    let node;
    while ((node=walker.nextNode())) updateText(node);
    document.body.querySelectorAll('[alt],[title],[aria-label],[placeholder],img,[data-src]').forEach(el => {
      if (el.closest('[data-no-translate],[contenteditable]')) return;
      attrs.forEach(key => updateAttribute(el,key,false));
      if (el.tagName === 'IMG') updateAttribute(el,'src',true);
      updateAttribute(el,'data-src',true);
    });
    controls.querySelectorAll('button').forEach(button => button.setAttribute('aria-pressed',String(button.dataset.language === language)));
    document.title = language === 'en' ? 'Crime Tapes — Investigation Files' : 'CRIME TAPES — ملف التحقيق';
    observer.observe(document.body,{childList:true,subtree:true,characterData:true,attributes:true,attributeFilter:[...attrs,'src','data-src']});
  }
  function schedule() {
    if (scheduled) return;
    scheduled = true;
    queueMicrotask(apply);
  }
  function setLanguage(next) {
    if (!['ar','en'].includes(next)) return;
    language = next;
    try { localStorage.setItem(preferenceKey,next); } catch (_) { /* In-memory selection still works. */ }
    apply();
    // Board labels can change width when the language changes.
    if (state.sec === 'map' && typeof root.drawLines === 'function') requestAnimationFrame(() => root.drawLines());
  }
  function init(canonicalData,viewState) {
    data = canonicalData;
    state = viewState;
    try { language = localStorage.getItem(preferenceKey) === 'en' ? 'en' : 'ar'; } catch (_) {}
    controls = document.createElement('nav');
    controls.id = 'language-controls';
    controls.setAttribute('aria-label','Language / اللغة');
    controls.setAttribute('data-no-translate','');
    controls.innerHTML = '<button type="button" lang="ar" dir="rtl" data-language="ar">العربية</button><button type="button" lang="en" dir="ltr" data-language="en" title="English preview — case 1 translated; remaining cases in progress">English <small>Preview</small></button>';
    const translatedCases = Object.keys(catalog.coverage).filter(id => !catalog.coverage[id].missing.length).length;
    controls.querySelector('[data-language="en"]').title = 'English preview — ' + translatedCases + ' cases translated; remaining cases in progress';
    controls.addEventListener('click',event => { const button=event.target.closest('[data-language]'); if(button) setLanguage(button.dataset.language); });
    document.body.prepend(controls);
    observer = new MutationObserver(schedule);
    root.addEventListener('storage',event => { if(event.key===preferenceKey) {language=event.newValue==='en'?'en':'ar'; apply();} });
    apply();
  }
  root.CTLocale = {init,refresh:apply,setLanguage,get language(){return language;}};
})(typeof window === 'object' ? window : globalThis);
