(function() {
  "use strict";

  var context = null;

  function signal(value) {
    var subs = [];
    var s = {
      get value() {
        if (context && subs.indexOf(context) === -1) subs.push(context);
        return value;
      },
      set value(v) {
        if (v === value) return;
        value = v;
        var toRun = subs.slice();
        for (var i = 0; i < toRun.length; i++) toRun[i]();
      },
      peek: function() { return value; }
    };
    return s;
  }

  function computed(fn) {
    var s = signal(undefined);
    effect(function() { s.value = fn(); });
    return { get value() { return s.value; }, peek: function() { return s.peek(); } };
  }

  function effect(fn) {
    var run = function() {
      var prev = context;
      context = run;
      try { fn(); } finally { context = prev; }
    };
    run();
  }

  var batchQueue = null;

  function batch(fn) {
    if (batchQueue) { fn(); return; }
    batchQueue = [];
    try {
      fn();
    } finally {
      var q = batchQueue;
      batchQueue = null;
      for (var i = 0; i < q.length; i++) q[i]();
    }
  }

  function transition(el, show, name) {
    var p = name || 'v';
    if (!el.__van_t) { el.__van_t = true; el.style.display = show ? '' : 'none'; return; }
    if (show) {
      el.style.display = '';
      el.classList.add(p + '-enter-from', p + '-enter-active');
      requestAnimationFrame(function() { requestAnimationFrame(function() {
        el.classList.remove(p + '-enter-from');
        el.classList.add(p + '-enter-to');
        var done = function() {
          el.classList.remove(p + '-enter-active', p + '-enter-to');
          el.removeEventListener('transitionend', done);
        };
        el.addEventListener('transitionend', done);
      }); });
    } else {
      el.classList.add(p + '-leave-from', p + '-leave-active');
      requestAnimationFrame(function() { requestAnimationFrame(function() {
        el.classList.remove(p + '-leave-from');
        el.classList.add(p + '-leave-to');
        var done = function() {
          el.classList.remove(p + '-leave-active', p + '-leave-to');
          el.style.display = 'none';
          el.removeEventListener('transitionend', done);
        };
        el.addEventListener('transitionend', done);
      }); });
    }
  }

  function watch(source, fn) {
    var prev;
    var first = true;
    effect(function() {
      var val = typeof source === 'function' ? source() : source.value;
      if (!first) { fn(val, prev); }
      prev = val;
      first = false;
    });
  }

  // watchEffect: auto-tracking effect (alias for effect, Vue 3 compatible)
  function watchEffect(fn) {
    effect(fn);
  }

  // reactive(): Proxy-based object reactivity
  function reactive(obj) {
    var signals = {};
    var keys = Object.keys(obj);
    for (var i = 0; i < keys.length; i++) {
      signals[keys[i]] = signal(obj[keys[i]]);
    }
    return new Proxy(obj, {
      get: function(_, key) {
        var s = signals[key];
        return s ? s.value : undefined;
      },
      set: function(_, key, val) {
        obj[key] = val;
        var s = signals[key];
        if (s) { s.value = val; }
        else { signals[key] = signal(val); }
        return true;
      }
    });
  }

  // Lifecycle hooks
  var mountedCallbacks = [];
  var unmountedCallbacks = [];

  function onMounted(fn) { mountedCallbacks.push(fn); }
  function onUnmounted(fn) { unmountedCallbacks.push(fn); }

  function _flushMounted() {
    for (var i = 0; i < mountedCallbacks.length; i++) mountedCallbacks[i]();
    mountedCallbacks = [];
  }
  function _flushUnmounted() {
    for (var i = 0; i < unmountedCallbacks.length; i++) unmountedCallbacks[i]();
    unmountedCallbacks = [];
  }

  // emit: dispatch CustomEvent on component root
  function emit(el, name, detail) {
    el.dispatchEvent(new CustomEvent(name, { detail: detail, bubbles: true }));
  }

  // teleport: move element to target selector
  function teleport(el, target) {
    var dest = typeof target === 'string' ? document.querySelector(target) : target;
    if (dest && el.parentNode !== dest) { dest.appendChild(el); }
  }

  // transitionGroup: manage enter/leave for list children
  function transitionGroup(container, name) {
    var p = name || 'v';
    var prev = [];
    return {
      update: function() {
        var curr = Array.from(container.children);
        // Enter: new children not in prev
        for (var i = 0; i < curr.length; i++) {
          if (prev.indexOf(curr[i]) === -1) {
            curr[i].classList.add(p + '-enter-from', p + '-enter-active');
            requestAnimationFrame(function(el) { return function() { requestAnimationFrame(function() {
              el.classList.remove(p + '-enter-from');
              el.classList.add(p + '-enter-to');
              var done = function() {
                el.classList.remove(p + '-enter-active', p + '-enter-to');
                el.removeEventListener('transitionend', done);
              };
              el.addEventListener('transitionend', done);
            }); }; }(curr[i]));
          }
        }
        // Leave: prev children not in curr — animate then remove
        for (var j = 0; j < prev.length; j++) {
          if (curr.indexOf(prev[j]) === -1 && prev[j].parentNode) {
            (function(el) {
              el.classList.add(p + '-leave-from', p + '-leave-active');
              requestAnimationFrame(function() { requestAnimationFrame(function() {
                el.classList.remove(p + '-leave-from');
                el.classList.add(p + '-leave-to');
                var done = function() {
                  el.classList.remove(p + '-leave-active', p + '-leave-to');
                  el.removeEventListener('transitionend', done);
                  if (el.parentNode) el.parentNode.removeChild(el);
                };
                el.addEventListener('transitionend', done);
              }); });
            })(prev[j]);
          }
        }
        prev = curr;
      }
    };
  }

  window.__VAN_NS__ = {
    signal: signal,
    computed: computed,
    effect: effect,
    batch: batch,
    transition: transition,
    watch: watch,
    watchEffect: watchEffect,
    reactive: reactive,
    onMounted: onMounted,
    onUnmounted: onUnmounted,
    emit: emit,
    teleport: teleport,
    transitionGroup: transitionGroup,
    _flushMounted: _flushMounted,
    _flushUnmounted: _flushUnmounted
  };

  // Auto-flush lifecycle hooks
  if (typeof document !== 'undefined') {
    if (document.readyState === 'loading') {
      document.addEventListener('DOMContentLoaded', function() { _flushMounted(); });
    } else {
      setTimeout(_flushMounted, 0);
    }
    window.addEventListener('beforeunload', function() { _flushUnmounted(); });
  }
})();
