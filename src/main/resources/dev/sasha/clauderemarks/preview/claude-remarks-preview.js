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
// narrows them onto the highlighted words itself, by searching for `text` in the source slice: it
// reads startFrom and endTo, and uses startTo and endFrom only to check that this message is in
// order. See the PreviewSelection KDoc on the IDE side for why they are still posted.
//
// It also carries messages the other way (phase 14): the IDE pushes which remarks exist for this
// file as a JSON array of {offset, kind}, and this script marks the innermost element covering each
// offset — walked up to the nearest block, because inside a paragraph the innermost element is a
// per-source-line span. See preview/PreviewRemarkExtension.kt's HIGHLIGHTS_MESSAGE_TYPE KDoc for the
// exact shape, and PreviewHighlights.kt for why the highlight can only ever be element-granular,
// never character-exact: md-src-pos holds source offsets, and rendered text is not source text.
(function () {
  "use strict";

  const SELECTION_MESSAGE_TYPE = "claude-remarks/selection";
  const HIGHLIGHTS_MESSAGE_TYPE = "claude-remarks/highlights";

  const REMARK_CLASS = "claude-remarks-highlight-remark";
  const QUESTION_CLASS = "claude-remarks-highlight-question";
  const HIGHLIGHT_CLASSES = [REMARK_CLASS, QUESTION_CLASS];

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

  // Parses one element's own position attribute into {from, to}, or null when it carries none or the
  // value is not two integers. Shared by the selection walk below and by the highlight lookup further
  // down — both read the same attribute the same way.
  function positionRangeOf(element) {
    if (!element.hasAttribute(positionAttributeName)) {
      return null;
    }
    const parts = element.getAttribute(positionAttributeName).split("..");
    const from = Number.parseInt(parts[0], 10);
    const to = Number.parseInt(parts[1], 10);
    return Number.isInteger(from) && Number.isInteger(to) ? { from: from, to: to } : null;
  }

  function post(message) {
    window.__IntelliJTools.messagePipe.post(SELECTION_MESSAGE_TYPE, message);
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
      if (current.nodeType === Node.ELEMENT_NODE) {
        const range = positionRangeOf(current);
        if (range) {
          return range;
        }
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

  // --- Highlighting annotated elements (phase 14) ---------------------------------------------
  //
  // Direction is the opposite of everything above: the IDE pushes, this script applies. There is no
  // request/response here either, for the same reason the selection side has none — the IDE cannot
  // know when a highlight should reappear except by being told, and re-render is exactly that kind of
  // event.

  // The last list the IDE pushed, in the [{offset, kind}, ...] shape PreviewHighlights.kt's toJson
  // writes. Kept so a re-render can re-apply it: the preview rebuilds its DOM as the source is typed
  // (IncrementalDOM.patch, on the platform side), and a class added by hand is gone the moment that
  // patch next touches the element, whether or not the element's own content actually changed.
  let lastHighlights = null;

  // Every element on the page that carries a position range, with its range already parsed, plus
  // whether the page holds anything narrower than the whole source.
  //
  // Collected ONCE per applyHighlights call, not once per highlight. applyHighlights runs from the
  // MutationObserver on every DOM rebuild, which is every keystroke in the source, so a fresh
  // querySelectorAll and a fresh attribute parse per remark would be O(remarks x elements) of string
  // work on the browser's UI thread per keystroke. ui/preview/jcef/ScrollSync.js caches its own
  // collection the same way and for the same reason.
  function positionedElements() {
    const found = document.querySelectorAll("[" + positionAttributeName + "]");
    const entries = [];
    let widestFrom = null;
    let widestTo = null;
    for (let i = 0; i < found.length; i++) {
      const range = positionRangeOf(found[i]);
      if (!range) {
        continue;
      }
      entries.push({ element: found[i], from: range.from, to: range.to });
      if (widestFrom === null || range.from < widestFrom) {
        widestFrom = range.from;
      }
      if (widestTo === null || range.to > widestTo) {
        widestTo = range.to;
      }
    }
    // Whether anything on the page is narrower than the whole source. See coversWholeSource below.
    const hasNarrower = entries.some(function (entry) {
      return entry.from > widestFrom || entry.to < widestTo;
    });
    return { entries: entries, from: widestFrom, to: widestTo, hasNarrower: hasNarrower };
  }

  // Whether this element's range covers the whole rendered source.
  //
  // The markdown generator writes a range on the document's own root element too, spanning the entire
  // file — ScrollSync.js reads exactly that element's second offset as the maximum offset in the page,
  // and IncrementalDOMBuilder renders the generator's <body> as a plain div, so the root is an
  // ordinary element INSIDE document.body and the selection side's "stop at document.body" guard never
  // sees it. Marking it puts a background and a left border on the entire rendered document, which is
  // what happened whenever an offset landed where no block covers it: a blank line, the gap between two
  // blocks, or a stale offset after the source moved.
  //
  // Only discarded when the page has something narrower to offer. A document whose every element spans
  // the whole file — one paragraph, one line, no trailing newline — has no root to tell apart, and
  // dropping the match there would lose the only highlight it can draw.
  function coversWholeSource(entry, positioned) {
    return positioned.hasNarrower && entry.from <= positioned.from && entry.to >= positioned.to;
  }

  // The innermost element whose position range contains offset, or null when none does — an ordinary
  // outcome, not an error, since the source moves as a person edits and an offset from an older
  // resolve can simply no longer land on anything.
  //
  // querySelectorAll returns elements in document order, and the generator that writes the position
  // attribute opens outer tags before inner ones. So among the elements whose range contains offset,
  // the LAST one in that order is the deepest — no recursion and no DOM walk of our own, one pass over
  // the collection above, keeping the last match standing.
  function elementForOffset(offset, positioned) {
    let innermost = null;
    for (let i = 0; i < positioned.entries.length; i++) {
      const entry = positioned.entries[i];
      if (offset >= entry.from && offset < entry.to && !coversWholeSource(entry, positioned)) {
        innermost = entry.element;
      }
    }
    return innermost;
  }

  // The nearest block-level element at or above the given one.
  //
  // The innermost match inside a paragraph is NOT the <p>. The markdown plugin's
  // ParagraphGeneratingProvider writes one <span> per SOURCE LINE inside a paragraph, each carrying its
  // own position range, so the deepest element covering a remark's offset is usually that inline span.
  // The offset arithmetic still lands on the right words, but a 3px left border on an inline box draws
  // a vertical bar in the middle of running text instead of down the left edge of a block, and both
  // README.md and docs/claude/design.md describe the unit as the paragraph, the list item or the
  // heading. So the class goes on the nearest ancestor that is actually a block.
  //
  // The walk stops at document.body for the same reason the selection walk does, and falls back to the
  // element it started from when it finds nothing — a highlight drawn slightly wrong is better than
  // none at all.
  function nearestBlock(element) {
    let current = element;
    while (current && current !== document.body) {
      const display = window.getComputedStyle(current).display;
      if (display && !display.startsWith("inline") && display !== "contents") {
        return current;
      }
      current = current.parentElement;
    }
    return element;
  }

  function clearHighlightClasses() {
    HIGHLIGHT_CLASSES.forEach(function (cls) {
      const marked = document.querySelectorAll("." + cls);
      for (let i = 0; i < marked.length; i++) {
        marked[i].classList.remove(cls);
      }
    });
  }

  function applyHighlights(highlights) {
    clearHighlightClasses();
    if (highlights.length === 0) {
      return;
    }
    const positioned = positionedElements();
    highlights.forEach(function (highlight) {
      const element = elementForOffset(highlight.offset, positioned);
      if (!element) {
        return; // the source moved since this offset was resolved; silent, not an error
      }
      nearestBlock(element).classList.add(highlight.kind === "question" ? QUESTION_CLASS : REMARK_CLASS);
    });
  }

  // Re-applies the last list on every DOM rebuild.
  //
  // Watches childList and characterData, deliberately never attributes: adding our own class through
  // classList.add is itself an attribute mutation, and an observer that also watched attributes would
  // see its own write and loop forever re-applying itself. childList/characterData already catch
  // every real re-render, because typing changes either the text of a node in place or the structure
  // around it, and neither of those is something this script ever writes.
  //
  // Watches document.documentElement, not document.body: this script runs from a <script> tag inside
  // <head>, before the parser has reached </head>, so document.body does not exist yet at this point
  // and observing it here would fail. document.documentElement (<html>) exists as soon as its own
  // opening tag is parsed, which is before <head>'s own children, and observing it with subtree:true
  // still covers every mutation inside body once body exists.
  const observer = new MutationObserver(function () {
    if (lastHighlights) {
      applyHighlights(lastHighlights);
    }
  });
  observer.observe(document.documentElement, { childList: true, subtree: true, characterData: true });

  window.__IntelliJTools.messagePipe.subscribe(HIGHLIGHTS_MESSAGE_TYPE, function (data) {
    let highlights;
    try {
      highlights = JSON.parse(data);
    } catch (error) {
      return;
    }
    if (!Array.isArray(highlights)) {
      return;
    }
    lastHighlights = highlights;
    applyHighlights(highlights);
  });
})();
