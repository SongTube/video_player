// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

import 'dart:async';
import 'dart:ui';

import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';

// ignore: import_of_legacy_library_into_null_safe
import 'messages.dart';
import 'video_player_platform_interface.dart';

/// An implementation of [VideoPlayerPlatform] that uses method channels.
class MethodChannelVideoPlayer extends VideoPlayerPlatform {
  VideoPlayerApi _api = VideoPlayerApi();

  @override
  Future<void> init() {
    return _api.initialize();
  }

  @override
  Future<void> dispose(int textureId) {
    return _api.dispose(TextureMessage()..textureId = textureId);
  }

  @override
  Future<int?> create(DataSource dataSource) async {
    CreateMessage message = CreateMessage();

    switch (dataSource.sourceType) {
      case DataSourceType.asset:
        message.videoAsset = dataSource.videoAsset;
        message.audioAsset = dataSource.audioAsset;
        message.packageName = dataSource.package;
        break;
      case DataSourceType.network:
        message.videoUri = dataSource.videoUri;
        message.audioUri = dataSource.audioUri;
        message.formatHint = _videoFormatStringMap[dataSource.formatHint];
        message.httpHeaders = dataSource.httpHeaders;
        break;
      case DataSourceType.file:
        message.videoUri = dataSource.videoUri;
        message.audioUri = dataSource.audioUri;
        break;
      case DataSourceType.contentUri:
        message.videoUri = dataSource.videoUri;
        message.audioUri = dataSource.audioUri;
        break;
    }

    TextureMessage response = await _api.create(message);
    return response.textureId;
  }

  @override
  Future<void> setLooping(int textureId, bool looping) {
    return _api.setLooping(LoopingMessage()
      ..textureId = textureId
      ..isLooping = looping);
  }

  @override
  Future<void> play(int textureId) {
    return _api.play(TextureMessage()..textureId = textureId);
  }

  @override
  Future<void> pause(int textureId) {
    return _api.pause(TextureMessage()..textureId = textureId);
  }

  @override
  Future<void> setVolume(int textureId, double volume) {
    return _api.setVolume(VolumeMessage()
      ..textureId = textureId
      ..volume = volume);
  }

  @override
  Future<void> setPlaybackSpeed(int textureId, double speed) {
    assert(speed > 0);

    return _api.setPlaybackSpeed(PlaybackSpeedMessage()
      ..textureId = textureId
      ..speed = speed);
  }

  @override
  Future<void> setVideoUrl(int textureId, String url) async {
    await _api.setVideoUrl(StringMessage()
      ..textureId = textureId
      ..text = url);
  }

  @override
  Future<void> setAudioUrl(int textureId, String url) async {
    await _api.setAudioUrl(StringMessage()
      ..textureId = textureId
      ..text = url);
  }

  @override
  Future<void> seekTo(int textureId, Duration position) {
    return _api.seekTo(PositionMessage()
      ..textureId = textureId
      ..position = position.inMilliseconds);
  }

  @override
  Future<Duration> getPosition(int textureId) async {
    PositionMessage response =
        await _api.position(TextureMessage()..textureId = textureId);
    return Duration(milliseconds: response.position!);
  }

  @override
  Stream<VideoEvent> videoEventsFor(int textureId) {
    return _eventChannelFor(textureId)
        .receiveBroadcastStream()
        .map((dynamic event) {
      final Map<dynamic, dynamic> map = event;
      switch (map['event']) {
        case 'initialized':
          return VideoEvent(
            eventType: VideoEventType.initialized,
            duration: Duration(milliseconds: map['duration']),
            size: Size(map['width']?.toDouble() ?? 0.0,
                map['height']?.toDouble() ?? 0.0),
          );
        case 'completed':
          return VideoEvent(
            eventType: VideoEventType.completed,
          );
        case 'bufferingUpdate':
          final List<dynamic> values = map['values'];

          return VideoEvent(
            buffered: values.map<DurationRange>(_toDurationRange).toList(),
            eventType: VideoEventType.bufferingUpdate,
          );
        case 'bufferingStart':
          return VideoEvent(eventType: VideoEventType.bufferingStart);
        case 'bufferingEnd':
          return VideoEvent(eventType: VideoEventType.bufferingEnd);
        default:
          return VideoEvent(eventType: VideoEventType.unknown);
      }
    });
  }

  @override
  Widget buildView(int textureId) {
    return Texture(textureId: textureId);
  }

  @override
  Future<void> setMixWithOthers(bool mixWithOthers) {
    return _api.setMixWithOthers(
      MixWithOthersMessage()..mixWithOthers = mixWithOthers,
    );
  }

  EventChannel _eventChannelFor(int textureId) {
    return EventChannel('flutter.io/videoPlayer/videoEvents$textureId');
  }

  static const Map<VideoFormat, String> _videoFormatStringMap =
      <VideoFormat, String>{
    VideoFormat.ss: 'ss',
    VideoFormat.hls: 'hls',
    VideoFormat.dash: 'dash',
    VideoFormat.other: 'other',
  };

  DurationRange _toDurationRange(dynamic value) {
    final List<dynamic> pair = value;
    return DurationRange(
      Duration(milliseconds: pair[0]),
      Duration(milliseconds: pair[1]),
    );
  }
}
