#!/usr/bin/env bash
# Baixa o motor de voz neural (sherpa-onnx) e a voz Faber (Piper, pt-BR) para dentro do projeto Android.
# Os arquivos são grandes, então não ficam no git: o GitHub Actions roda este script antes de compilar.
set -euo pipefail
SHERPA_VER="1.13.8"
VOZ="pt_BR-faber-medium"
RAIZ="$(cd "$(dirname "$0")/.." && pwd)"
LIBS="$RAIZ/android/app/libs"
ASSETS="$RAIZ/android/app/src/main/assets/piper"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$LIBS" "$ASSETS/faber"

if [ ! -s "$LIBS/sherpa-onnx.aar" ]; then
  echo "Baixando sherpa-onnx $SHERPA_VER…"
  curl -fsSL --retry 4 -o "$TMP/sherpa.aar" "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$SHERPA_VER/sherpa-onnx-$SHERPA_VER.aar"
  # só celulares ARM (64 e 32 bits) e só a biblioteca usada pelo Java: o APK fica bem menor
  (cd "$TMP" && mkdir aar && cd aar && unzip -q ../sherpa.aar && rm -rf jni/x86 jni/x86_64 jni/*/libsherpa-onnx-c-api.so jni/*/libsherpa-onnx-cxx-api.so && zip -q -r "$LIBS/sherpa-onnx.aar" .)
fi

if [ ! -s "$ASSETS/faber/model.onnx" ] || [ ! -s "$ASSETS/espeak-ng-data.zip" ]; then
  echo "Baixando a voz $VOZ…"
  curl -fsSL --retry 4 -o "$TMP/voz.tar.bz2" "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-$VOZ.tar.bz2"
  tar xjf "$TMP/voz.tar.bz2" -C "$TMP"
  D="$TMP/vits-piper-$VOZ"
  cp "$D/$VOZ.onnx" "$ASSETS/faber/model.onnx"
  cp "$D/tokens.txt" "$ASSETS/faber/tokens.txt"
  cp "$D/MODEL_CARD" "$ASSETS/faber/MODEL_CARD"
  # dados de pronúncia: só o português e o que é comum a todas as línguas
  E="$D/espeak-ng-data"
  (cd "$D" && rm -f "$ASSETS/espeak-ng-data.zip" && zip -q -r "$ASSETS/espeak-ng-data.zip" espeak-ng-data/phondata espeak-ng-data/phondata-manifest espeak-ng-data/phonindex espeak-ng-data/phontab espeak-ng-data/intonations espeak-ng-data/pt_dict espeak-ng-data/lang espeak-ng-data/voices)
fi
ls -la "$LIBS" "$ASSETS" "$ASSETS/faber"
