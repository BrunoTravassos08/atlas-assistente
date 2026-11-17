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
# palavra de ativação "Atlas" (offline): modelo de palavra-chave do sherpa-onnx, versão int8 (~5 MB)
KWS="$RAIZ/android/app/src/main/assets/kws"
KWS_MODELO="sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01"
if [ ! -s "$KWS/encoder.onnx" ]; then
  echo "Baixando o modelo da palavra Atlas…"
  mkdir -p "$KWS"
  curl -fsSL --retry 4 -o "$TMP/kws.tar.bz2" "https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/$KWS_MODELO.tar.bz2"
  tar xjf "$TMP/kws.tar.bz2" -C "$TMP"
  K="$TMP/$KWS_MODELO"
  cp "$K/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx" "$KWS/encoder.onnx"
  cp "$K/decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx" "$KWS/decoder.onnx"
  cp "$K/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx" "$KWS/joiner.onnx"
  cp "$K/tokens.txt" "$KWS/tokens.txt"
fi
# jeitos de pronunciar "Atlas" (em tokens do modelo, gerados com sherpa-onnx-cli text2token)
cat > "$KWS/keywords.txt" <<'PALAVRAS'
▁AT LA S @ATLAS
▁AT ▁LA S @ATLAS
▁AT ▁LE S S @ATLAS
▁AT ▁ LU S @ATLAS
▁A D ▁LA S @ATLAS
▁A H ▁T LA S @ATLAS
▁AT ▁LO S @ATLAS
▁OUT ▁LA S @ATLAS
▁AT ▁LAST @ATLAS
▁AT ▁LE AS T @ATLAS
▁AT LA @ATLAS
▁AT LA S ▁E @ATLAS
▁AT ▁LA @ATLAS
▁AT TI LA ▁S @ATLAS
▁AT ▁T I LL ▁US @ATLAS
▁AT ▁A ▁LA S @ATLAS
▁AT ▁CHI ▁LA S @ATLAS
▁AT ▁I ▁LE S S @ATLAS
▁AT T IL AS @ATLAS
▁AT ▁LI S @ATLAS
▁AT ▁LA S ▁S @ATLAS
▁UP ▁LA S @ATLAS
▁AT ▁RU S @ATLAS
▁ ACT ▁LE S S @ATLAS
PALAVRAS
ls -la "$LIBS" "$ASSETS" "$ASSETS/faber" "$KWS"
