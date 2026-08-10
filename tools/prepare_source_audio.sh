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
project_dir="$(cd "$script_dir/.." && pwd)"
output_dir="$project_dir/app/src/main/res/raw"
target_lufs="-26"
target_peak="-2"
target_lra="7"

audio_work_dir="$(mktemp -d "${TMPDIR:-/tmp}/whitenoise-tv-audio.XXXXXX")"
cleanup() {
  if [[ -d "$audio_work_dir" && "$audio_work_dir" == *"/whitenoise-tv-audio."* ]]; then
    rm -rf -- "$audio_work_dir"
  fi
}
trap cleanup EXIT

mkdir -p "$output_dir"

read_loudness_metric() {
  local analysis_text="$1"
  local metric_name="$2"
  printf '%s\n' "$analysis_text" | awk -F'"' -v key="$metric_name" \
    '$2 == key { value = $4 } END { print value }'
}

make_loop() {
  local source_path="$1"
  local source_offset="$2"
  local output_name="$3"
  local raw_loop="$audio_work_dir/raw-$output_name"
  local normalized_loop="$audio_work_dir/normalized-$output_name"

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
    -map "[out]" -vn -c:a pcm_f32le -ar 44100 -ac 2 \
    "$raw_loop"

  local loudness_analysis
  if ! loudness_analysis="$("$ffmpeg_bin" -hide_banner -nostats \
      -i "$raw_loop" \
      -af "loudnorm=I=$target_lufs:TP=$target_peak:LRA=$target_lra:print_format=json" \
      -f null - 2>&1)"; then
    printf '%s\n' "$loudness_analysis" >&2
    exit 70
  fi

  local input_i input_tp input_lra input_thresh target_offset
  input_i="$(read_loudness_metric "$loudness_analysis" input_i)"
  input_tp="$(read_loudness_metric "$loudness_analysis" input_tp)"
  input_lra="$(read_loudness_metric "$loudness_analysis" input_lra)"
  input_thresh="$(read_loudness_metric "$loudness_analysis" input_thresh)"
  target_offset="$(read_loudness_metric "$loudness_analysis" target_offset)"

  if [[ -z "$input_i" || -z "$input_tp" || -z "$input_lra" \
      || -z "$input_thresh" || -z "$target_offset" ]]; then
    echo "Could not parse FFmpeg loudness analysis for $output_name" >&2
    exit 70
  fi

  "$ffmpeg_bin" -hide_banner -loglevel warning -y \
    -i "$raw_loop" \
    -af "loudnorm=I=$target_lufs:TP=$target_peak:LRA=$target_lra:\
measured_I=$input_i:measured_TP=$input_tp:measured_LRA=$input_lra:\
measured_thresh=$input_thresh:offset=$target_offset:linear=true" \
    -c:a pcm_s16le -ar 44100 -ac 2 "$normalized_loop"

  mv "$normalized_loop" "$output_dir/$output_name"
  echo "$output_name: input $input_i LUFS, normalized to $target_lufs LUFS"
}

# Offsets were selected after checking multiple regions for stable ambience,
# speech/music contamination, clipping, and visual spectral consistency.
make_loop "$1" 10800 soft_rain.wav
make_loop "$2" 4800 heavy_rain.wav
make_loop "$3" 3600 ocean_waves.wav

echo "Prepared three loudness-balanced, 120-second loop-ready WAV files in $output_dir"
