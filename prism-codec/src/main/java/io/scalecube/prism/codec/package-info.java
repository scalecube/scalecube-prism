/**
 * Schema'd wire codecs (msgpack/protobuf) for registry deltas, consensus log entries, and elector
 * messages. Replaces JDK native serialization on the wire to close the deserialization RCE surface.
 */
package io.scalecube.prism.codec;
