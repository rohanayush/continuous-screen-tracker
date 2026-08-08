<script setup lang="ts">
import { computed, ref, watch, onMounted } from "vue";

// The real tracking / reset / reminder logic runs in the native Android
// foreground service (ScreenTimerService.kt). This screen is a calm status
// panel: the same countdown the service is running, drawn so you can see it,
// the rest interval, and one persistent note.

/** MainActivity exposes this; absent in a browser or during `vite dev`. */
type Bridge = {
  getIntervalMs(): string;
  getChoices(): string;
  setIntervalMs(value: string): void;
};
const bridge = (): Bridge | undefined =>
  (window as unknown as { EyeRest?: Bridge }).EyeRest;

const DEFAULT_MS = 20 * 60 * 1000;
const FALLBACK_CHOICES = [20_000, 300_000, 600_000, 1_200_000, 1_800_000, 3_600_000];

const choices = ref<number[]>(FALLBACK_CHOICES);
const intervalMs = ref(DEFAULT_MS);

/** "20s", "5 min", "1 hr" — short enough for a chip. */
function label(ms: number): string {
  if (ms < 60_000) return `${Math.round(ms / 1000)}s`;
  const mins = Math.round(ms / 60_000);
  return mins === 60 ? "1 hr" : `${mins} min`;
}
const intervalLabel = computed(() => label(intervalMs.value));

function choose(ms: number) {
  intervalMs.value = ms;
  const b = bridge();
  if (b) b.setIntervalMs(String(ms));
  else localStorage.setItem("eyeRestInterval", String(ms)); // preview builds
}

// One simple note, editable by default, persisted in the WebView's storage.
const note = ref("");

onMounted(() => {
  note.value = localStorage.getItem("eyeRestNote") ?? "";

  const b = bridge();
  if (b) {
    const parsed = b.getChoices().split(",").map(Number).filter((n) => n > 0);
    if (parsed.length) choices.value = parsed;
    intervalMs.value = Number(b.getIntervalMs()) || DEFAULT_MS;
  } else {
    intervalMs.value = Number(localStorage.getItem("eyeRestInterval")) || DEFAULT_MS;
  }
});

watch(note, (v) => localStorage.setItem("eyeRestNote", v));
</script>

<template>
  <main class="screen">
    <!-- ===== The eye: the service's countdown, made visible ===== -->
    <section class="stage" aria-hidden="true">
      <span class="halo"></span>

      <svg class="ring" viewBox="0 0 120 120">
        <circle class="track" cx="60" cy="60" r="52" />
        <circle class="progress" cx="60" cy="60" r="52" />
      </svg>

      <span class="eye">
        <span class="iris"><i class="pupil"></i><i class="spark"></i></span>
        <span class="lid"></span>
      </span>
    </section>

    <header class="head">
      <h1>Eye Rest Reminder</h1>
      <p class="status"><i class="pip"></i> Running quietly in the background</p>
    </header>

    <!-- ===== Threshold, and the choice of it ===== -->
    <section class="dial">
      <div class="dial-value">{{ intervalLabel }}</div>
      <p class="dial-label">of continuous screen time, then a reminder to look away</p>

      <fieldset class="picker">
        <legend>Remind me after</legend>
        <div class="picker-row">
          <button
            v-for="ms in choices"
            :key="ms"
            type="button"
            class="pick"
            :class="{ on: ms === intervalMs }"
            :aria-pressed="ms === intervalMs"
            @click="choose(ms)"
          >
            {{ label(ms) }}
          </button>
        </div>
      </fieldset>
    </section>

    <!-- ===== Note ===== -->
    <section class="note-card">
      <label class="note-label" for="note">My note</label>
      <textarea
        id="note"
        v-model="note"
        class="note"
        placeholder="Anything you want to keep in front of you…"
        spellcheck="false"
      ></textarea>
    </section>

    <!-- ===== How it works ===== -->
    <section class="how">
      <h2>How it works</h2>
      <ol>
        <li>
          <span class="step-dot"></span>
          Counting starts the moment your screen turns on.
        </li>
        <li>
          <span class="step-dot"></span>
          After {{ intervalLabel }} of continuous use, a full-screen reminder
          asks you to rest your eyes.
        </li>
        <li>
          <span class="step-dot"></span>
          Type your <strong>pet name</strong> to dismiss it — set it the first
          time; after that, close enough is enough.
        </li>
        <li>
          <span class="step-dot"></span>
          Locking the phone, or rebooting, sets the timer back to zero.
        </li>
      </ol>
    </section>

    <aside class="hint">
      <strong>One-time setup</strong>
      Installed from an APK, so Android locks the permission this needs. Open
      <em>Settings → Apps → Eye Rest Reminder</em>, tap the <em>⋮</em> in the
      top-right and choose <em>“Allow restricted settings”</em> — the only item
      there. Then switch on <em>Display over other apps</em>.
    </aside>

    <p class="footer">Rest early, rest often. Your eyes do the rest.</p>
  </main>
</template>

<style>
/* ---------------------------------------------------------------------------
   A screen you look at when your eyes already hurt: low luminance, nothing
   pure white, nothing saturated, and every motion slow enough to ignore.
--------------------------------------------------------------------------- */
:root {
  /* the countdown mirrors the service's real threshold */
  --cycle: 20s;

  --bg: #0b0d13;
  --bg-soft: #12151d;
  --line: rgba(255, 255, 255, 0.07);
  --ink: #ccd3e1; /* deliberately not #fff — softer on tired eyes */
  --ink-dim: #8891a4;
  --accent: #86a8e0;
  --accent-soft: rgba(134, 168, 224, 0.16);
  --warm: #d9b783;

  font-family: Inter, "Segoe UI", system-ui, -apple-system, sans-serif;
  color: var(--ink);
  background-color: var(--bg);
  -webkit-font-smoothing: antialiased;
  color-scheme: dark;
}

* { box-sizing: border-box; }

html, body {
  margin: 0;
  padding: 0;
  background: var(--bg);
  /* a slow, barely-there wash so the screen is never a flat slab of black */
  background-image:
    radial-gradient(680px 380px at 50% -6%, rgba(134, 168, 224, 0.1), transparent 70%),
    radial-gradient(520px 320px at 10% 105%, rgba(120, 200, 190, 0.06), transparent 70%);
  background-attachment: fixed;
}

.screen {
  max-width: 460px;
  margin: 0 auto;
  padding: 3.4vh 1.25rem 4vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
}

/* ---- The eye ---- */
.stage {
  position: relative;
  width: 100%;
  height: 210px;
  display: grid;
  place-items: center;
  margin-bottom: 0.4rem;
}
.stage::before {
  content: "";
  position: absolute;
  inset: 0;
  background: repeating-radial-gradient(
    circle at 50% 50%,
    rgba(134, 168, 224, 0.05) 0 1px,
    transparent 1px 20px
  );
  mask-image: radial-gradient(closest-side at 50% 50%, #000 25%, transparent 100%);
}
.halo {
  position: absolute;
  width: 210px;
  height: 210px;
  border-radius: 50%;
  background: radial-gradient(closest-side, rgba(134, 168, 224, 0.22), transparent 72%);
  filter: blur(10px);
  animation: halo-breathe var(--cycle) ease-in-out infinite;
}

.ring {
  position: absolute;
  width: 186px;
  height: 186px;
  transform: rotate(-90deg);
  overflow: visible;
}
.ring circle { fill: none; stroke-width: 2.6; stroke-linecap: round; }
.track { stroke: rgba(255, 255, 255, 0.07); }
.progress {
  stroke: var(--accent);
  stroke-dasharray: 327;
  stroke-dashoffset: 0;
  filter: drop-shadow(0 0 5px rgba(134, 168, 224, 0.45));
  animation: countdown var(--cycle) linear infinite;
}

.eye {
  position: relative;
  width: 116px;
  height: 68px;
  border-radius: 50% / 50%;
  background: linear-gradient(180deg, #e6e9f0, #b9c2d4);
  box-shadow: inset 0 -3px 8px rgba(16, 20, 34, 0.4), 0 10px 26px -12px rgba(0, 0, 0, 0.9);
  display: grid;
  place-items: center;
  overflow: hidden;
}
.iris {
  position: relative;
  width: 42px;
  height: 42px;
  border-radius: 50%;
  background: radial-gradient(circle at 36% 32%, #6f8fd0, #2b3556 72%);
  display: grid;
  place-items: center;
}
.pupil { width: 18px; height: 18px; border-radius: 50%; background: #0d1018; }
.spark {
  position: absolute;
  top: 7px;
  left: 8px;
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.8);
}
.lid {
  position: absolute;
  inset: 0 0 auto 0;
  height: 26%;
  background: linear-gradient(180deg, #141822, #1c2130);
  border-bottom: 2px solid rgba(0, 0, 0, 0.35);
  border-radius: 50% 50% 12px 12px / 70% 70% 12px 12px;
  animation: rest-blink var(--cycle) ease-in-out infinite;
}

/* the timer winding down, the eye closing as it lands */
@keyframes countdown {
  0%   { stroke-dashoffset: 0; }
  82%  { stroke-dashoffset: 327; }
  84%  { stroke-dashoffset: 327; }
  96%  { stroke-dashoffset: 0; }
  100% { stroke-dashoffset: 0; }
}
@keyframes rest-blink {
  0%, 74%   { height: 26%; }
  84%, 91%  { height: 100%; }
  97%, 100% { height: 26%; }
}
@keyframes halo-breathe {
  0%, 72%, 100% { opacity: 0.7; transform: scale(1); }
  86%           { opacity: 1;   transform: scale(1.1); }
}

/* ---- Head ---- */
.head { margin-bottom: 1.6rem; }
h1 {
  margin: 0 0 0.45rem;
  font-size: 1.5rem;
  font-weight: 600;
  letter-spacing: -0.01em;
  color: #dbe1ec;
}
.status {
  margin: 0;
  font-size: 0.86rem;
  color: var(--ink-dim);
  display: inline-flex;
  align-items: center;
  gap: 0.45rem;
}
.pip {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #7fc7a6;
  box-shadow: 0 0 0 3px rgba(127, 199, 166, 0.14);
  animation: pip 4s ease-in-out infinite;
}
@keyframes pip {
  0%, 100% { opacity: 0.55; }
  50%      { opacity: 1; }
}

/* ---- Threshold dial ---- */
.dial {
  width: 100%;
  background: var(--bg-soft);
  border: 1px solid var(--line);
  border-radius: 20px;
  padding: 1.3rem 1.2rem;
  margin-bottom: 1.1rem;
}
.dial-value {
  font-size: 2.6rem;
  font-weight: 600;
  color: var(--accent);
  line-height: 1;
  letter-spacing: -0.02em;
}
.dial-value small { font-size: 1.1rem; font-weight: 500; opacity: 0.7; margin-left: 0.1em; }
.dial-label {
  margin: 0.6rem 0 0;
  font-size: 0.88rem;
  line-height: 1.55;
  color: var(--ink-dim);
}

/* ---- Interval picker ---- */
.picker {
  border: 0;
  border-top: 1px solid var(--line);
  margin: 1.2rem 0 0;
  padding: 1.1rem 0 0;
}
.picker legend {
  padding: 0;
  font-size: 0.7rem;
  text-transform: uppercase;
  letter-spacing: 0.14em;
  color: var(--ink-dim);
  font-weight: 700;
}
.picker-row {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 0.4rem;
  margin-top: 0.8rem;
}
.pick {
  font: inherit;
  font-size: 0.86rem;
  font-weight: 600;
  color: var(--ink-dim);
  background: rgba(255, 255, 255, 0.03);
  border: 1px solid var(--line);
  border-radius: 999px;
  padding: 0.42rem 0.85rem;
  cursor: pointer;
  transition: color 0.3s ease, background 0.3s ease, border-color 0.3s ease;
}
.pick:hover { color: var(--ink); border-color: rgba(134, 168, 224, 0.35); }
.pick.on {
  color: #0b0d13;
  background: var(--accent);
  border-color: var(--accent);
}

/* ---- Note ---- */
.note-card {
  width: 100%;
  text-align: left;
  margin-bottom: 1.6rem;
}
.note-label {
  display: block;
  font-size: 0.75rem;
  text-transform: uppercase;
  letter-spacing: 0.14em;
  color: var(--ink-dim);
  margin: 0 0 0.5rem 0.2rem;
}
.note {
  width: 100%;
  min-height: 116px;
  resize: vertical;
  background: var(--bg-soft);
  color: var(--ink);
  border: 1px solid var(--line);
  border-radius: 16px;
  padding: 0.95rem 1.05rem;
  font-size: 0.98rem;
  line-height: 1.65;
  font-family: inherit;
  transition: border-color 0.35s ease, box-shadow 0.35s ease;
}
.note::placeholder { color: rgba(136, 145, 164, 0.65); }
.note:focus {
  outline: none;
  border-color: rgba(134, 168, 224, 0.5);
  box-shadow: 0 0 0 4px var(--accent-soft);
}

/* ---- How it works ---- */
.how { width: 100%; text-align: left; margin-bottom: 1.3rem; }
.how h2 {
  font-size: 0.75rem;
  text-transform: uppercase;
  letter-spacing: 0.14em;
  color: var(--ink-dim);
  font-weight: 700;
  margin: 0 0 0.8rem 0.2rem;
}
.how ol {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: 0.75rem;
}
.how li {
  position: relative;
  padding-left: 1.5rem;
  font-size: 0.93rem;
  line-height: 1.62;
  color: var(--ink-dim);
}
.how strong { color: var(--ink); font-weight: 600; }
.step-dot {
  position: absolute;
  left: 0;
  top: 0.62em;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--accent);
  opacity: 0.75;
}

/* ---- Hint ---- */
.hint {
  width: 100%;
  text-align: left;
  background: rgba(217, 183, 131, 0.07);
  border: 1px solid rgba(217, 183, 131, 0.22);
  border-radius: 16px;
  padding: 0.95rem 1.05rem;
  font-size: 0.88rem;
  line-height: 1.6;
  color: var(--ink-dim);
}
.hint strong {
  display: block;
  color: var(--warm);
  font-weight: 600;
  margin-bottom: 0.25rem;
}
.hint em { color: var(--ink); font-style: italic; }

.footer {
  margin: 1.8rem 0 0;
  font-size: 0.8rem;
  color: rgba(136, 145, 164, 0.6);
}

/* Someone who asked for less motion is exactly who this app is for. */
@media (prefers-reduced-motion: reduce) {
  .progress, .lid, .halo, .pip { animation: none; }
  .progress { stroke-dashoffset: 90; }
}
</style>
