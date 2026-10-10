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
