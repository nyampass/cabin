$(function() {
  var wsUri = "ws://cabin.nyampass.com/ws";
  var $output = $('#output');
  var websocket;

  function startWebsocket() {
    websocket = new WebSocket(wsUri);
    websocket.onopen = function(evt) { onOpen(evt) };
    websocket.onclose = function(evt) { onClose(evt) };
    websocket.onmessage = function(evt) { onMessage(evt) };
    websocket.onerror = function(evt) { onError(evt) };
  }

  function onOpen(evt) {
    appendMessage("CONNECTED");
  }

  function onClose(evt) {
    appendMessage("DISCONNECTED");
  }

  function onMessage(evt) {
    appendMessage('<span style="color: blue;">RESPONSE: ' + evt.data+'</span>');
  }

  function onError(evt) {
    appendMessage('<span style="color: red;">ERROR:</span> ' + evt.data);
  }

  function doSend(message) {
    appendMessage("SENT: " + message);
    websocket.send(message);
  }

  function appendMessage(message) {
    var $pre = $("<p/>").css("word-wrap", "break-word")
                        .html(message);
    $output.append($pre);
  }

  startWebsocket();
  var $input = $('#input');
  var $submit = $('#submit');

  $submit.click(function() {
    var message = $input.val();
    doSend(message);
  });
})
