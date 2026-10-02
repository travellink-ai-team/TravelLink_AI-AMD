(function (root, factory) {
  'use strict';

  var api = factory();
  if (typeof module === 'object' && module.exports) {
    module.exports = api;
  } else {
    root.WAI_RECAP_CAMERA = api;
  }
}(typeof self !== 'undefined' ? self : this, function () {
  'use strict';

  function isFiniteNumber(value) {
    return typeof value === 'number' && isFinite(value);
  }

  function clamp(value, min, max) {
    return Math.max(min, Math.min(max, value));
  }

  function numberOr(value, fallback) {
    return isFiniteNumber(value) ? value : fallback;
  }

  function createFollowCam(opts) {
    opts = opts || {};

    if (!opts.viewport || !isFiniteNumber(opts.viewport.w) || !isFiniteNumber(opts.viewport.h)) {
      throw new TypeError('createFollowCam requires viewport: { w, h }');
    }

    var viewport = { w: opts.viewport.w, h: opts.viewport.h };
    var minScale = numberOr(opts.minScale, 0.2);
    var maxScale = numberOr(opts.maxScale, 8);
    if (minScale > maxScale) {
      var swapped = minScale;
      minScale = maxScale;
      maxScale = swapped;
    }

    var smooth = clamp(numberOr(opts.smooth, 0.12), 0, 1);
    var lookAheadPx = numberOr(opts.lookAheadPx, 0);
    var centerX = 0;
    var centerY = 0;
    var scale = clamp(numberOr(opts.scale, 1), minScale, maxScale);

    function reset(position) {
      if (!position || !isFiniteNumber(position.x) || !isFiniteNumber(position.y)) {
        throw new TypeError('reset requires { x, y }');
      }
      centerX = position.x;
      centerY = position.y;
    }

    function setScale(nextScale) {
      scale = clamp(numberOr(nextScale, scale), minScale, maxScale);
    }

    function update(input) {
      if (!input || !input.target || !isFiniteNumber(input.target.x) || !isFiniteNumber(input.target.y)) {
        throw new TypeError('update requires target: { x, y }');
      }

      var heading = numberOr(input.heading, 0);
      var targetX = input.target.x + Math.cos(heading) * lookAheadPx;
      var targetY = input.target.y + Math.sin(heading) * lookAheadPx;

      // This convex interpolation cannot overshoot when smooth is in [0, 1].
      centerX += (targetX - centerX) * smooth;
      centerY += (targetY - centerY) * smooth;

      return { centerX: centerX, centerY: centerY, scale: scale };
    }

    function worldToScreen(position) {
      if (!position || !isFiniteNumber(position.x) || !isFiniteNumber(position.y)) {
        throw new TypeError('worldToScreen requires { x, y }');
      }
      return {
        x: (position.x - centerX) * scale + viewport.w / 2,
        y: (position.y - centerY) * scale + viewport.h / 2
      };
    }

    function getCenter() {
      return { x: centerX, y: centerY };
    }

    function getScale() {
      return scale;
    }

    return {
      reset: reset,
      setScale: setScale,
      update: update,
      worldToScreen: worldToScreen,
      getCenter: getCenter,
      getScale: getScale
    };
  }

  return { createFollowCam: createFollowCam };
}));
