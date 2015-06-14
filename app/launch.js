chrome.app.runtime.onLaunched.addListener(function() {
  chrome.app.window.create('client.html', {
    'bounds': {
      'width': 480,
      'height': 480
    }
  });
});
