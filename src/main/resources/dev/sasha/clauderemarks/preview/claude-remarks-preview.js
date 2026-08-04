// The browser half of a remark written on the rendered markdown preview.
//
// It listens for selection changes and posts one message per change. The IDE never asks the page
// anything: preview/PreviewRemarkExtension.kt keeps the last message, and the right-click action
// reads what is kept. See that file for why the page pushes rather than answers.
//
// The message is JSON with exactly five fields, which preview/PreviewSelection.kt parses:
//   startFrom, startTo  the position range of the nearest ancestor where the selection starts
//   endFrom,   endTo    the same for where it ends
//   text                what the person actually highlighted
// A page can only name whole elements, so the two ranges are usually whole source lines. The IDE
// narrows them onto the highlighted words itself, by searching for `text` in the source slice.
(function () {
  "use strict";

  const MESSAGE_TYPE = "claude-remarks/selection";

  // What "nothing is highlighted any more" looks like on the wire. The IDE's parser refuses an empty
  // text, and its answer to every refusal is the same: forget whatever was stored. So this needs no
  // field of its own, and there is one shape of message rather than two.
  const NOTHING_SELECTED = JSON.stringify({ startFrom: 0, startTo: 0, endFrom: 0, endTo: 0, text: "" });

  // A drag fires selectionchange on nearly every mouse move. One report per window is plenty: the
  // window is far shorter than the time between letting go of the mouse and reaching the context
  // menu, so the last report always describes the finished selection.
  const THROTTLE_MS = 100;

  // The attribute's name is read from the page rather than written here, exactly as
  // ui/preview/jcef/ScrollSync.js does. The platform decides what it is called, and a rename would
  // otherwise turn into a script that silently finds nothing.
  const meta = document.querySelector('meta[name="markdown-position-attribute-name"]');
  if (!meta) {
    return;
  }
  const positionAttributeName = meta.content;

  function post(message) {
    window.__IntelliJTools.messagePipe.post(MESSAGE_TYPE, message);
  }

  // The nearest ancestor carrying a position range, or null when the walk reaches the body without
  // finding one.
  //
  // The walk is the whole point of this function. A selection lands in a text node, and text nodes
  // carry no attributes at all; inline HTML the person typed into the document carries none either.
  // Body is excluded deliberately: its range is the whole file, which would turn every stray
  // selection into a remark about the entire document.
  function nearestPosition(node) {
    let current = node;
    while (current && current !== document.body) {
      if (current.nodeType === Node.ELEMENT_NODE && current.hasAttribute(positionAttributeName)) {
        const parts = current.getAttribute(positionAttributeName).split("..");
        const from = Number.parseInt(parts[0], 10);
        const to = Number.parseInt(parts[1], 10);
        return Number.isInteger(from) && Number.isInteger(to) ? { from: from, to: to } : null;
      }
      current = current.parentNode;
    }
    return null;
  }

  function report() {
    const selection = window.getSelection();
    if (!selection || selection.isCollapsed || selection.rangeCount === 0) {
      post(NOTHING_SELECTED);
      return;
    }
    const text = selection.toString();
    if (text.length === 0) {
      post(NOTHING_SELECTED);
      return;
    }
    const range = selection.getRangeAt(0);
    const start = nearestPosition(range.startContainer);
    const end = nearestPosition(range.endContainer);
    // Either walk failing clears the stored entry rather than leaving the previous one in place. The
    // person has selected something this script cannot describe, and answering their next right
    // click with an older selection would be worse than answering it with nothing.
    if (!start || !end) {
      post(NOTHING_SELECTED);
      return;
    }
    post(JSON.stringify({
      startFrom: start.from,
      startTo: start.to,
      endFrom: end.from,
      endTo: end.to,
      text: text
    }));
  }

  let waiting = false;
  document.addEventListener("selectionchange", function () {
    if (waiting) {
      return;
    }
    waiting = true;
    setTimeout(function () {
      waiting = false;
      report();
    }, THROTTLE_MS);
  });
})();
