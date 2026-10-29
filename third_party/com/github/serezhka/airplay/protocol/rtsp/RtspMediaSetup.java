package com.github.serezhka.airplay.protocol.rtsp;

import com.dd.plist.BinaryPropertyListParser;
import com.dd.plist.NSDictionary;
import com.github.serezhka.airplay.protocol.media.AirPlayStreamType;
import com.github.serezhka.airplay.protocol.media.AudioStreamInfo;
import com.github.serezhka.airplay.protocol.media.MediaStreamInfo;
import com.github.serezhka.airplay.protocol.media.VideoStreamInfo;
import net.i2p.crypto.eddsa.Utils;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Optional;

public class RtspMediaSetup {

    private static final com.github.serezhka.airplay.protocol.Log log = com.github.serezhka.airplay.protocol.Log.of(RtspMediaSetup.class);


    private byte[] ekey;
    private byte[] eiv;

    private String streamConnectionID;

    public Optional<MediaStreamInfo> setup(InputStream rtspSetupPayload) throws Exception {
        NSDictionary setup = (NSDictionary) BinaryPropertyListParser.parse(rtspSetupPayload);
        if (setup.containsKey("ekey") || setup.containsKey("eiv")) {
            ekey = (byte[]) setup.get("ekey").toJavaObject();
            eiv = (byte[]) setup.get("eiv").toJavaObject();
            if (log.isDebugEnabled()) {
                log.debug("Encrypted AES key: {}, iv: {}", Utils.bytesToHex(ekey), Utils.bytesToHex(eiv));
            }
            return Optional.empty();
        } else if (setup.containsKey("streams")) {
            if (log.isDebugEnabled()) {
                log.debug("RTSP SETUP streams:\n{}", setup.toXMLPropertyList());
            }
            return Optional.ofNullable(getMediaStreamInfo(setup));
        } else {
            if (log.isDebugEnabled()) {
                log.error("Unknown RTSP setup content\n{}", setup.toXMLPropertyList());
            } else {
                log.error("Unknown RTSP setup content");
            }
            return Optional.empty();
        }
    }

    public Optional<MediaStreamInfo> teardown(InputStream rtspTeardownPayload) throws Exception {
        NSDictionary teardown = (NSDictionary) BinaryPropertyListParser.parse(rtspTeardownPayload);
        if (log.isDebugEnabled()) {
            log.debug("RTSP TEARDOWN streams:\n{}", teardown.toXMLPropertyList());
        }
        if (teardown.containsKey("streams")) {
            return Optional.ofNullable(getMediaStreamInfo(teardown));
        }
        return Optional.empty();
    }

    private MediaStreamInfo getMediaStreamInfo(NSDictionary request) {
        Object[] streams = ((Object[]) request.get("streams").toJavaObject());
        if (streams.length > 1) {
            log.warn("Request contains more than one stream info");
        }

        //noinspection rawtypes
        HashMap stream = (HashMap) streams[0];
        int type = ((Number) stream.get("type")).intValue();
        switch (type) {

            // video stream (screen mirroring)
            case AirPlayStreamType.SCREEN: {
                if (stream.containsKey("streamConnectionID")) {
                    Object rawId = stream.get("streamConnectionID");
                    long id = rawId instanceof Number ? ((Number) rawId).longValue() : Long.parseLong(rawId.toString());
                    streamConnectionID = Long.toUnsignedString(id);
                }
                return new VideoStreamInfo(streamConnectionID);
            }

            // realtime audio
            case AirPlayStreamType.AUDIO_REALTIME: {
                AudioStreamInfo.AudioStreamInfoBuilder builder = new AudioStreamInfo.AudioStreamInfoBuilder();
                if (stream.containsKey("ct")) {
                    int compressionType = ((Number) stream.get("ct")).intValue();
                    builder.compressionType(AudioStreamInfo.CompressionType.fromCode(compressionType));
                }
                if (stream.containsKey("audioFormat")) {
                    long audioFormatCode = ((Number) stream.get("audioFormat")).longValue();
                    builder.audioFormat(AudioStreamInfo.AudioFormat.fromCode(audioFormatCode));
                }
                if (stream.containsKey("spf")) {
                    int samplesPerFrame = ((Number) stream.get("spf")).intValue();
                    builder.samplesPerFrame(samplesPerFrame);
                }
                return builder.build();
            }

            default: {
                log.error("Unknown stream type: {}", type);
                return null;
            }
        }
    }

    public String getStreamConnectionID() {
        return streamConnectionID;
    }

    public byte[] getEkey() {
        return ekey;
    }

    public byte[] getEiv() {
        return eiv;
    }
}
