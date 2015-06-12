chrome.app.runtime.onLaunched.addListener(function() {
  chrome.app.window.create('client.html', {
    'bounds': {
      'width': 800,
      'height': 180
    }
  });
});
