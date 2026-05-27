(() => {
  if (window.__czytnikStronyLoaded) {
    return;
  }

  window.__czytnikStronyLoaded = true;

  const MAX_TEXT_LENGTH = 120000;
  const MAX_UTTERANCE_LENGTH = 260;
  const DEFAULT_LANG = "pl-PL";
  const DEFAULT_RATE = 0.92;
  const TRANSLATE_CHUNK_SIZE = 3000;
  const TRANSLATE_MAX_CHARS = 15000;
  let isReadingQueue = false;
  let stopPickingStart = null;

  function normalizeText(text) {
    return (text || "")
      .replace(/\s+/g, " ")
      .replace(/\s+([,.!?;:])/g, "$1")
      .trim();
  }

  function clampRate(rate) {
    const value = Number(rate);
    if (!Number.isFinite(value)) {
      return DEFAULT_RATE;
    }

    return Math.min(1.8, Math.max(0.6, value));
  }

  function getSelectedText() {
    return normalizeText(window.getSelection?.().toString());
  }

  function getElementText(selector) {
    const element = document.querySelector(selector);
    return normalizeText(element?.innerText);
  }

  function getBestPageText() {
    const candidates = [
      ["article", "article"],
      ["main", "main"],
      ["body", "strona"]
    ];

    for (const [selector, source] of candidates) {
      const text = selector === "body"
        ? normalizeText(document.body?.innerText)
        : getElementText(selector);

      if (text) {
        return {
          text: text.slice(0, MAX_TEXT_LENGTH),
          source
        };
      }
    }

    return { text: "", source: "" };
  }

  function getTextFromSelectionToEnd() {
    const selectedText = getSelectedText();
    const pageText = getBestPageText();

    if (!selectedText || !pageText.text) {
      return { text: "", source: "" };
    }

    const index = pageText.text.indexOf(selectedText);
    if (index === -1) {
      return {
        text: selectedText,
        source: "zaznaczenie"
      };
    }

    return {
      text: pageText.text.slice(index),
      source: "od zaznaczenia"
    };
  }

  function getReadableText(mode = "auto") {
    const selectedText = getSelectedText();

    if (mode === "selection") {
      return selectedText
        ? { text: selectedText, source: "zaznaczenie" }
        : { text: "", source: "" };
    }

    if (mode === "fromSelection") {
      return getTextFromSelectionToEnd();
    }

    if (mode === "article") {
      const text = getElementText("article");
      return text ? { text: text.slice(0, MAX_TEXT_LENGTH), source: "article" } : { text: "", source: "" };
    }

    if (mode === "main") {
      const text = getElementText("main");
      return text ? { text: text.slice(0, MAX_TEXT_LENGTH), source: "main" } : { text: "", source: "" };
    }

    if (mode === "body") {
      const text = normalizeText(document.body?.innerText);
      return text ? { text: text.slice(0, MAX_TEXT_LENGTH), source: "strona" } : { text: "", source: "" };
    }

    if (selectedText) {
      return {
        text: selectedText,
        source: "zaznaczenie"
      };
    }

    return getBestPageText();
  }

  function waitForVoices() {
    const voices = window.speechSynthesis.getVoices();
    if (voices.length) {
      return Promise.resolve(voices);
    }

    return new Promise((resolve) => {
      const timeoutId = window.setTimeout(() => {
        window.speechSynthesis.onvoiceschanged = null;
        resolve(window.speechSynthesis.getVoices());
      }, 800);

      window.speechSynthesis.onvoiceschanged = () => {
        window.clearTimeout(timeoutId);
        window.speechSynthesis.onvoiceschanged = null;
        resolve(window.speechSynthesis.getVoices());
      };
    });
  }

  function normalizeLanguageCode(lang) {
    const cleaned = (lang || "").replace("_", "-").trim();
    if (!cleaned) {
      return "";
    }

    const [base, region] = cleaned.split("-");
    return region ? `${base.toLowerCase()}-${region.toUpperCase()}` : base.toLowerCase();
  }

  function expandLanguageCode(lang) {
    const normalized = normalizeLanguageCode(lang);
    const defaults = {
      pl: "pl-PL",
      en: "en-US",
      de: "de-DE",
      fr: "fr-FR",
      es: "es-ES",
      it: "it-IT"
    };

    return defaults[normalized] || normalized;
  }

  function getPageLanguage() {
    const metaLanguage = document.querySelector("meta[http-equiv='content-language']")?.content
      || document.querySelector("meta[property='og:locale']")?.content;

    return normalizeLanguageCode(document.documentElement.lang || metaLanguage || navigator.language);
  }

  function detectLanguageWithChrome(text) {
    if (!chrome.i18n?.detectLanguage) {
      return Promise.resolve("");
    }

    return new Promise((resolve) => {
      chrome.i18n.detectLanguage(text.slice(0, 6000), (result) => {
        if (chrome.runtime.lastError || !result?.isReliable) {
          resolve("");
          return;
        }

        const bestLanguage = result.languages
          ?.filter((language) => language.percentage >= 35)
          ?.sort((a, b) => b.percentage - a.percentage)[0];

        resolve(normalizeLanguageCode(bestLanguage?.language));
      });
    });
  }

  function detectLanguageHeuristic(text) {
    const sample = ` ${text.slice(0, 6000).toLowerCase()} `;
    const scores = {
      "pl-PL": [" \u017ce ", " nie ", " jest ", " si\u0119 ", " na ", " do ", " oraz ", " kt\u00f3ry ", "\u0105", "\u0107", "\u0119", "\u0142", "\u0144", "\u00f3", "\u015b", "\u017a", "\u017c"],
      "en-US": [" the ", " and ", " is ", " are ", " with ", " from ", " that ", " this "],
      "de-DE": [" der ", " die ", " das ", " und ", " ist ", " nicht ", " mit ", " von ", " auf ", "\u00e4", "\u00f6", "\u00fc", "\u00df"],
      "fr-FR": [" le ", " la ", " les ", " des ", " est ", " avec ", " pour ", " dans ", " que ", "\u00e9", "\u00e0", "\u00e8", "\u00e7"],
      "es-ES": [" el ", " la ", " los ", " las ", " que ", " para ", " con ", " una ", " est\u00e1 ", "\u00f1"],
      "it-IT": [" il ", " lo ", " la ", " gli ", " che ", " per ", " con ", " una ", " non ", "\u00e8"]
    };

    let bestLang = "";
    let bestScore = 0;

    for (const [lang, markers] of Object.entries(scores)) {
      const score = markers.reduce((total, marker) => total + (sample.includes(marker) ? 1 : 0), 0);
      if (score > bestScore) {
        bestScore = score;
        bestLang = lang;
      }
    }

    return bestScore >= 2 ? bestLang : "";
  }

  async function detectLanguage(text) {
    return expandLanguageCode(await detectLanguageWithChrome(text)
      || detectLanguageHeuristic(text)
      || getPageLanguage()
      || DEFAULT_LANG);
  }

  async function translateChunk(text) {
    const url = `https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=pl&dt=t&q=${encodeURIComponent(text)}`;
    const response = await fetch(url);
    if (!response.ok) {
      throw new Error(`HTTP ${response.status}`);
    }
    const data = await response.json();
    return data[0].map((item) => item[0]).join("");
  }

  async function translateToPolish(text) {
    const limited = text.slice(0, TRANSLATE_MAX_CHARS);
    const chunks = [];
    for (let i = 0; i < limited.length; i += TRANSLATE_CHUNK_SIZE) {
      chunks.push(limited.slice(i, i + TRANSLATE_CHUNK_SIZE));
    }

    const results = [];
    for (const chunk of chunks) {
      results.push(await translateChunk(chunk));
    }
    return results.join(" ");
  }

  function scoreVoice(voice) {
    let score = 0;
    if (/natural/i.test(voice.name)) score += 8;
    if (/online/i.test(voice.name)) score += 4;
    if (/microsoft/i.test(voice.name)) score += 2;
    if (/google/i.test(voice.name)) score += 2;
    return score;
  }

  function getPreferredVoiceNamePattern(lang) {
    const baseLang = lang.split("-")[0].toLowerCase();
    const patterns = {
      pl: /paulina|polish|polski|pl-pl/i,
      en: /zira|david|mark|english|en-/i,
      de: /hedda|katja|german|deutsch|de-/i,
      fr: /hortense|julie|french|francais|fr-/i,
      es: /helena|pablo|spanish|espanol|es-/i,
      it: /elsa|cosimo|italian|italiano|it-/i
    };

    return patterns[baseLang] || new RegExp(baseLang, "i");
  }

  async function getAvailableVoices() {
    const voices = await waitForVoices();
    return voices
      .map((voice) => ({
        voiceURI: voice.voiceURI,
        name: voice.name,
        lang: voice.lang,
        default: voice.default,
        localService: voice.localService
      }))
      .sort((a, b) => {
        const aIsPl = a.lang?.toLowerCase().startsWith("pl");
        const bIsPl = b.lang?.toLowerCase().startsWith("pl");
        if (aIsPl && !bIsPl) return -1;
        if (!aIsPl && bIsPl) return 1;
        if (aIsPl && bIsPl) return scoreVoice(b) - scoreVoice(a);
        return `${a.lang} ${a.name}`.localeCompare(`${b.lang} ${b.name}`);
      });
  }

  async function getVoiceForLanguage(lang, preferredVoiceURI) {
    const voices = await waitForVoices();
    const selectedVoice = voices.find((voice) => voice.voiceURI === preferredVoiceURI);
    if (selectedVoice) {
      return selectedVoice;
    }

    const normalizedLang = expandLanguageCode(lang) || DEFAULT_LANG;
    const baseLang = normalizedLang.split("-")[0].toLowerCase();
    const exactVoices = voices.filter((voice) => normalizeLanguageCode(voice.lang) === normalizedLang);
    const languageVoices = voices.filter((voice) => voice.lang?.toLowerCase().startsWith(baseLang));

    if (baseLang === "pl") {
      const pool = exactVoices.length ? exactVoices : languageVoices;
      if (pool.length) {
        return pool.slice().sort((a, b) => scoreVoice(b) - scoreVoice(a))[0];
      }
    }

    const preferredPattern = getPreferredVoiceNamePattern(normalizedLang);
    return exactVoices.find((voice) => preferredPattern.test(voice.name))
      || exactVoices[0]
      || languageVoices.find((voice) => preferredPattern.test(voice.name))
      || languageVoices[0]
      || null;
  }

  function splitIntoChunks(text) {
    const sentences = text.match(/[^.!?;:]+[.!?;:]?|\S+/g) || [];
    const chunks = [];
    let current = "";

    for (const sentence of sentences) {
      const next = current ? `${current} ${sentence.trim()}` : sentence.trim();

      if (next.length > MAX_UTTERANCE_LENGTH && current) {
        chunks.push(current);
        current = sentence.trim();
      } else {
        current = next;
      }
    }

    if (current) {
      chunks.push(current);
    }

    return chunks;
  }

  function speakQueue(chunks, voice, lang, rate, index = 0) {
    if (!isReadingQueue || index >= chunks.length) {
      isReadingQueue = false;
      return;
    }

    const utterance = new SpeechSynthesisUtterance(chunks[index]);
    utterance.lang = lang;
    utterance.rate = rate;
    utterance.pitch = 1;
    utterance.volume = 1;

    if (voice) {
      utterance.voice = voice;
    }

    utterance.onend = () => speakQueue(chunks, voice, lang, rate, index + 1);
    utterance.onerror = () => {
      isReadingQueue = false;
    };

    window.speechSynthesis.speak(utterance);
  }

  async function speak(text, options = {}) {
    window.speechSynthesis.cancel();
    isReadingQueue = true;

    let finalText = text;
    let lang = await detectLanguage(text);
    let translated = false;

    if (options.translate && !lang.startsWith("pl")) {
      try {
        finalText = await translateToPolish(text);
        lang = "pl-PL";
        translated = true;
      } catch {
        // fall back to original text
      }
    }

    const voice = await getVoiceForLanguage(lang, options.voiceURI);
    const rate = clampRate(options.rate);
    const chunks = splitIntoChunks(finalText);
    speakQueue(chunks, voice, lang, rate);
    return { voice, lang, rate, translated };
  }

  async function readText(readable, options) {
    if (!readable.text) {
      return { ok: false, message: "Nie znaleziono tekstu do czytania." };
    }

    const { voice, lang, rate, translated } = await speak(readable.text, options);
    const voiceMessage = voice ? `, glos: ${voice.name}` : ", brak pasujacego glosu w systemie";
    const translateMessage = translated ? ", przetlumaczono" : "";
    return { ok: true, message: `Czytam: ${readable.source}, jezyk: ${lang}, tempo: ${rate.toFixed(2)}${translateMessage}${voiceMessage}` };
  }

  function getTextFromClickedPlace(target) {
    const pageText = getBestPageText();
    const clickedElement = target.closest("p, li, h1, h2, h3, h4, h5, h6, blockquote, section, article, main")
      || target;
    const clickedText = normalizeText(clickedElement?.innerText || clickedElement?.textContent);

    if (!clickedText) {
      return { text: "", source: "" };
    }

    if (!pageText.text) {
      return {
        text: clickedText.slice(0, MAX_TEXT_LENGTH),
        source: "klikniete miejsce"
      };
    }

    const index = pageText.text.indexOf(clickedText);
    return {
      text: (index === -1 ? clickedText : pageText.text.slice(index)).slice(0, MAX_TEXT_LENGTH),
      source: "klikniete miejsce"
    };
  }

  function armStartPicker(options) {
    if (stopPickingStart) {
      stopPickingStart();
    }

    const previousCursor = document.documentElement.style.cursor;
    document.documentElement.style.cursor = "crosshair";

    const cleanup = () => {
      document.removeEventListener("click", onClick, true);
      document.removeEventListener("keydown", onKeydown, true);
      document.documentElement.style.cursor = previousCursor;
      stopPickingStart = null;
    };

    const onClick = (event) => {
      event.preventDefault();
      event.stopPropagation();
      cleanup();

      readText(getTextFromClickedPlace(event.target), options).catch(() => {});
    };

    const onKeydown = (event) => {
      if (event.key === "Escape") {
        cleanup();
      }
    };

    document.addEventListener("click", onClick, true);
    document.addEventListener("keydown", onKeydown, true);
    stopPickingStart = cleanup;
  }

  chrome.runtime.onMessage.addListener((request, _sender, sendResponse) => {
    if (request.command === "getVoices") {
      getAvailableVoices()
        .then((voices) => sendResponse({ ok: true, voices }))
        .catch(() => sendResponse({ ok: false, voices: [] }));
      return true;
    }

    if (request.command === "read") {
      readText(getReadableText(request.options?.mode), request.options)
        .then(sendResponse)
        .catch(() => sendResponse({ ok: false, message: "Nie udalo sie uruchomic czytania." }));
      return true;
    }

    if (request.command === "pickStart") {
      armStartPicker(request.options || {});
      sendResponse({ ok: true, message: "Kliknij miejsce na stronie." });
      return true;
    }

    if (request.command === "pauseResume") {
      if (window.speechSynthesis.paused) {
        window.speechSynthesis.resume();
        sendResponse({ ok: true, message: "Wznowiono czytanie." });
      } else if (window.speechSynthesis.speaking) {
        window.speechSynthesis.pause();
        sendResponse({ ok: true, message: "Czytanie wstrzymane." });
      } else {
        sendResponse({ ok: false, message: "Nic teraz nie jest czytane." });
      }

      return true;
    }

    if (request.command === "stop") {
      isReadingQueue = false;
      if (stopPickingStart) {
        stopPickingStart();
      }
      window.speechSynthesis.cancel();
      sendResponse({ ok: true, message: "Zatrzymano czytanie." });
      return true;
    }

    sendResponse({ ok: false, message: "Nieznane polecenie." });
    return true;
  });
})();
