(function(root, factory){
  if (typeof module !== 'undefined' && module.exports) module.exports = factory();
  else root.WAI_RECAP_ROUTE = factory();
})(typeof self !== 'undefined' ? self : this, function(){
  'use strict';

  /**
   * Decode a Google Encoded Polyline string into [latitude, longitude] pairs.
   *
   * @param {string} encoded
   * @param {number} [precision=5]
   * @returns {Array<[number, number]>}
   */
  function decodePolyline(encoded, precision) {
    if (encoded === '') return [];

    var factor = Math.pow(10, precision === undefined ? 5 : precision);
    var index = 0;
    var lat = 0;
    var lng = 0;
    var points = [];

    while (index < encoded.length) {
      var result = 0;
      var shift = 0;
      var byte;

      do {
        byte = encoded.charCodeAt(index++) - 63;
        result |= (byte & 0x1f) << shift;
        shift += 5;
      } while (byte >= 0x20 && index <= encoded.length);

      lat += (result & 1) ? ~(result >> 1) : (result >> 1);

      result = 0;
      shift = 0;
      do {
        byte = encoded.charCodeAt(index++) - 63;
        result |= (byte & 0x1f) << shift;
        shift += 5;
      } while (byte >= 0x20 && index <= encoded.length);

      lng += (result & 1) ? ~(result >> 1) : (result >> 1);
      points.push([lat / factor, lng / factor]);
    }

    return points;
  }

  function roundedCoordinate(value) {
    var rounded = Math.round(Number(value) * 1000000) / 1000000;
    // Avoid producing two keys for numeric zero and negative zero.
    return rounded === 0 ? '0.000000' : rounded.toFixed(6);
  }

  /**
   * Create a stable cache key from a route's endpoints and transport mode.
   */
  function encodeKey(fromLatLng, toLatLng, mode) {
    return roundedCoordinate(fromLatLng.lat) + ',' + roundedCoordinate(fromLatLng.lng) +
      '|' + roundedCoordinate(toLatLng.lat) + ',' + roundedCoordinate(toLatLng.lng) +
      '|' + String(mode);
  }

  /**
   * Create an in-memory route cache.
   */
  function createRouteCache() {
    var cache = Object.create(null);
    var count = 0;

    function key(from, to, mode) {
      return encodeKey(from, to, mode);
    }

    return {
      get: function(from, to, mode) {
        return cache[key(from, to, mode)];
      },
      set: function(from, to, mode, points) {
        var cacheKey = key(from, to, mode);
        if (!Object.prototype.hasOwnProperty.call(cache, cacheKey)) count += 1;
        cache[cacheKey] = points;
      },
      has: function(from, to, mode) {
        return Object.prototype.hasOwnProperty.call(cache, key(from, to, mode));
      },
      size: function() {
        return count;
      }
    };
  }

  /**
   * Return a two-point straight-line route when routing is unavailable.
   */
  function straightFallback(a, b) {
    return [[a.lat, a.lng], [b.lat, b.lng]];
  }

  return {
    decodePolyline: decodePolyline,
    encodeKey: encodeKey,
    createRouteCache: createRouteCache,
    straightFallback: straightFallback
  };
});
