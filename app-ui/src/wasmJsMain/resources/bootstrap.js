// Keep a useful message if the application cannot load on this browser.
function showLoadFailure() {
  const app = document.getElementById('app');
  if (!app || app.querySelector('canvas')) return;
  const message = document.createElement('p');
  message.textContent = 'The app could not load. Try the latest Chrome, Edge, Firefox or Safari, or download the desktop app.';
  message.className = 'loading';
  const loading = app.querySelector('.loading > p');
  if (loading) loading.replaceWith(message);
}
window.addEventListener('error', showLoadFailure, true);
window.addEventListener('unhandledrejection', showLoadFailure);

// A locally installed HA panel owns authentication and subscriptions. The iframe accepts
// snapshots only from its same-origin parent; credentials never cross this boundary.
window.terrazzoHost = {
  connect(receive) {
    if (window.parent === window) return;
    window.addEventListener('message', (event) => {
      if (event.source !== window.parent || event.origin !== window.location.origin) return;
      if (event.data?.type !== 'terrazzo:snapshot') return;
      receive(JSON.stringify(event.data));
    });
    window.parent.postMessage({type: 'terrazzo:ready'}, window.location.origin);
  },
};
