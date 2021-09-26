// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

import 'package:pigeon/pigeon.dart';

@ConfigurePigeon(PigeonOptions(
  input: 'pigeons/messages.dart',
  dartOut: 'lib/src/interface/messages.dart',
  objcHeaderOut: 'ios/Classes/messages.h',
  objcSourceOut: 'ios/Classes/messages.m',
  objcOptions: ObjcOptions(prefix: 'FLT'),
  javaOut: 'android/src/main/java/io/flutter/plugins/videoplayer/Messages.java',
  javaOptions: JavaOptions(package: 'io.flutter.plugins.videoplayer'),
))

class TextureMessage {
  int? textureId;
}

class LoopingMessage {
  int? textureId;
  bool? isLooping;
}

class VolumeMessage {
  int? textureId;
  double? volume;
}

class PlaybackSpeedMessage {
  int? textureId;
  double? speed;
}

class PositionMessage {
  int? textureId;
  int? position;
}

class StringMessage {
  int? textureId;
  String? text;
}
class CreateMessage {
  String? videoAsset;
  String? audioAsset;
  String? videoUri;
  String? audioUri;
  String? packageName;
  String? formatHint;
  Map<String?, String?>? httpHeaders;
}

class MixWithOthersMessage {
  bool? mixWithOthers;
}

@HostApi(dartHostTestHandler: 'TestHostVideoPlayerApi')
abstract class VideoPlayerApi {
  void initialize();
  TextureMessage create(CreateMessage msg);
  void dispose(TextureMessage msg);
  void setLooping(LoopingMessage msg);
  void setVolume(VolumeMessage msg);
  void setPlaybackSpeed(PlaybackSpeedMessage msg);
  void play(TextureMessage msg);
  PositionMessage position(TextureMessage msg);
  void setVideoUrl(StringMessage msg);
  void setAudioUrl(StringMessage msg);
  void seekTo(PositionMessage msg);
  void pause(TextureMessage msg);
  void setMixWithOthers(MixWithOthersMessage msg);
}