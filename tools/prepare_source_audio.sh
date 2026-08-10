#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 3 ]]; then
  echo "Usage: $0 <soft-rain-video> <heavy-rain-video> <ocean-waves-video>" >&2
  exit 64
fi

ffmpeg_bin="${FFMPEG_BIN:-ffmpeg}"
if ! command -v "$ffmpeg_bin" >/dev/null 2>&1; then
  echo "FFmpeg was not found. Install it or set FFMPEG_BIN." >&2
  exit 69
fi

script_dir="$(cd "$(dirname "$0")" && pwd)"
output_dir="$script_dir/../app/src/main/res/raw"
mkdir -p "$output_dir"

make_loop() {
  local source_path="$1"
  local source_offset="$2"
  local output_name="$3"

  if [[ ! -f "$source_path" ]]; then
    echo "Source file not found: $source_path" >&2
    exit 66
  fi

  "$ffmpeg_bin" -hide_banner -loglevel warning -y \
    -ss "$source_offset" -t 125 -i "$source_path" \
    -filter_complex \
      "[0:a]atrim=start=0:end=5,asetpts=PTS-STARTPTS[head];\
[0:a]atrim=start=5:end=120,asetpts=PTS-STARTPTS[middle];\
[0:a]atrim=start=120:end=125,asetpts=PTS-STARTPTS[tail];\
[tail][head]acrossfade=d=5:c1=tri:c2=tri[seam];\
[middle][seam]concat=n=2:v=0:a=1[out]" \
    -map "[out]" -vn -c:a pcm_s16le -ar 44100 -ac 2 \
    "$output_dir/$output_name"
}

# Offsets were selected after checking multiple regions for stable ambience,
# speech/music contamination, clipping, and visual spectral consistency.
make_loop "$1" 3600 soft_rain.wav
make_loop "$2" 4800 heavy_rain.wav
make_loop "$3" 3600 ocean_waves.wav

echo "Prepared three 120-second loop-ready WAV files in $output_dir"
