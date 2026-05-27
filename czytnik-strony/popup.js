const statusEl = document.getElementById("status");
const readButton = document.getElementById("read");
const pickStartButton = document.getElementById("pickStart");
const pauseResumeButton = document.getElementById("pauseResume");
const stopButton = document.getElementById("stop");
const voiceSelect = document.getElementById("voice");
const modeSelect = document.getElementById("mode");
const translateCheck = document.getElementById("translate");
const rateInput = document.getElementById("rate");
const rateValue = document.getElementById("rateValue");

const SETTINGS_KEY = "czytnikStronySettings";

function setStatus(message) {
  statusEl.textContent = message;
}

async function getActiveTab() {
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  if (!tab?.id) {
    throw new Error("Nie znaleziono aktywnej karty.");
  }
  return tab;
}

async function ensureContentScript(tabId) {
  await chrome.scripting.executeScript({
    target: { tabId },
    files: ["content.js"]
  });
}

function getSettings() {
  return {
    voiceURI: voiceSelect.value,
    mode: modeSelect.value,
    rate: Number(rateInput.value),
    translate: translateCheck.checked
  };
}

function saveSettings() {
  localStorage.setItem(SETTINGS_KEY, JSON.stringify(getSettings()));
}

function restoreSettings() {
  try {
    const settings = JSON.parse(localStorage.getItem(SETTINGS_KEY) || "{}");
    if (settings.mode) {
      modeSelect.value = settings.mode;
    }
    if (settings.rate) {
      rateInput.value = settings.rate;
    }
    if (settings.voiceURI) {
      voiceSelect.dataset.savedVoiceUri = settings.voiceURI;
    }
    if (settings.translate) {
      translateCheck.checked = settings.translate;
    }
  } catch {
    localStorage.removeItem(SETTINGS_KEY);
  }
}

function updateRateValue() {
  rateValue.textContent = `${Number(rateInput.value).toFixed(2)}x`;
}

async function sendCommand(command, options = undefined) {
  const tab = await getActiveTab();
  await ensureContentScript(tab.id);

  return chrome.tabs.sendMessage(tab.id, { command, options });
}

async function runCommand(command, pendingMessage, includeSettings = false) {
  setStatus(pendingMessage);

  try {
    const response = await sendCommand(command, includeSettings ? getSettings() : undefined);
    setStatus(response?.message || "Gotowe");
  } catch (error) {
    setStatus(error?.message || "Nie udalo sie wykonac polecenia.");
  }
}

async function loadVoices() {
  try {
    const response = await sendCommand("getVoices");
    const voices = response?.voices || [];
    const savedVoiceURI = voiceSelect.dataset.savedVoiceUri;

    const polishVoices = voices.filter((v) => v.lang?.toLowerCase().startsWith("pl"));
    const otherVoices = voices.filter((v) => !v.lang?.toLowerCase().startsWith("pl"));

    function appendVoiceOption(parent, voice) {
      const option = document.createElement("option");
      option.value = voice.voiceURI;
      option.textContent = `${voice.name} (${voice.lang})`;
      parent.append(option);
    }

    if (polishVoices.length > 0) {
      const group = document.createElement("optgroup");
      group.label = "Polski";
      for (const voice of polishVoices) {
        appendVoiceOption(group, voice);
      }
      voiceSelect.append(group);
    }

    if (otherVoices.length > 0) {
      const group = document.createElement("optgroup");
      group.label = "Inne języki";
      for (const voice of otherVoices) {
        appendVoiceOption(group, voice);
      }
      voiceSelect.append(group);
    }

    if (savedVoiceURI && voices.some((voice) => voice.voiceURI === savedVoiceURI)) {
      voiceSelect.value = savedVoiceURI;
    }
  } catch {
    setStatus("Nie udalo sie pobrac glosow.");
  }
}

readButton.addEventListener("click", () => {
  runCommand("read", "Pobieram tekst...", true);
});

pickStartButton.addEventListener("click", () => {
  runCommand("pickStart", "Czekam na klikniecie strony...", true);
});

pauseResumeButton.addEventListener("click", () => {
  runCommand("pauseResume", "Zmieniam stan...");
});

stopButton.addEventListener("click", () => {
  runCommand("stop", "Zatrzymuje...");
});

for (const element of [voiceSelect, modeSelect, translateCheck, rateInput]) {
  element.addEventListener("input", () => {
    updateRateValue();
    saveSettings();
  });
}

restoreSettings();
updateRateValue();
loadVoices();
