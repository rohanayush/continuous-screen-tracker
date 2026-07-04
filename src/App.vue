<script setup lang="ts">
import { ref, watch, onMounted } from "vue";

// The real tracking / reset / reminder logic runs in the native Android
// foreground service (ScreenTimerService.kt). This screen is a status panel
// plus a single persistent note.

const thresholdSeconds = 20; // matches THRESHOLD_MS in ScreenTimerService.kt

// One simple note, editable by default, persisted in the WebView's storage.
const note = ref("");
onMounted(() => {
  note.value = localStorage.getItem("eyeRestNote") ?? "";
});
watch(note, (v) => localStorage.setItem("eyeRestNote", v));
</script>

<template>
  <main class="container">
    <h1>👁️ Eye Rest Reminder</h1>
    <p class="status">Running automatically in the background.</p>

    <!-- One editable note, centered, editable by default -->
    <section class="note-card">
      <label class="note-label">My Note</label>
      <textarea
        v-model="note"
        class="note"
        placeholder="Write a note here… it stays saved."
        spellcheck="false"
      ></textarea>
    </section>

    <div class="card">
      <div class="big">{{ thresholdSeconds }}s</div>
      <div class="muted">continuous screen-on time before a rest reminder</div>
    </div>

    <ul class="how">
      <li>⏱️ Counting starts the moment the screen turns on.</li>
      <li>🌑 After {{ thresholdSeconds }}s of continuous use, a full-screen
        “take rest” reminder appears.</li>
      <li>🔒 Enter your <strong>pet name</strong> to dismiss the reminder
        (set it the first time; later ~70% is enough).</li>
      <li>🔄 Locking the phone (or rebooting) resets the timer to 0.</li>
    </ul>

    <div class="note-hint">
      <strong>One-time setup:</strong> allow
      <em>“Display over other apps”</em> when prompted — required to show the
      reminder on top of whatever you are using.
    </div>
  </main>
</template>

<style>
:root {
  font-family: Inter, Avenir, Helvetica, Arial, sans-serif;
  color: #f6f6f6;
  background-color: #1b1b1f;
  -webkit-font-smoothing: antialiased;
}

.container {
  margin: 0;
  padding: 6vh 1.2em 4vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
}

h1 {
  margin: 0 0 0.2em;
  font-size: 1.8rem;
}

.status {
  color: #4ade80;
  font-weight: 600;
  margin-top: 0;
}

.note-card {
  width: 100%;
  max-width: 440px;
  display: flex;
  flex-direction: column;
  align-items: center;
  margin: 1em 0 1.4em;
}

.note-label {
  font-size: 0.85rem;
  color: #a1a1aa;
  margin-bottom: 0.4em;
}

.note {
  width: 100%;
  min-height: 120px;
  resize: vertical;
  background: #26262b;
  color: #f6f6f6;
  border: 1px solid #3a3a42;
  border-radius: 14px;
  padding: 0.9em 1em;
  font-size: 1rem;
  line-height: 1.5;
  text-align: center;
  font-family: inherit;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.35);
}

.note:focus {
  outline: none;
  border-color: #60a5fa;
}

.card {
  background: #26262b;
  border-radius: 16px;
  padding: 1.2em 2em;
  margin: 0.4em 0 1.2em;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.4);
}

.big {
  font-size: 3rem;
  font-weight: 700;
  color: #60a5fa;
}

.muted {
  color: #a1a1aa;
  font-size: 0.9rem;
}

.how {
  text-align: left;
  max-width: 440px;
  line-height: 1.6;
  padding-left: 1.1em;
}

.note-hint {
  max-width: 440px;
  background: #3b2f12;
  border: 1px solid #a16207;
  color: #fde68a;
  border-radius: 12px;
  padding: 0.9em 1em;
  font-size: 0.9rem;
  margin-top: 0.6em;
}
</style>
