#!/usr/bin/env python3
"""Generate the original, loop-ready PCM beds bundled by White Noise TV.

The sounds are intentionally synthetic ambience rather than recordings. Each
bed is made deterministic, normalized, and converted into a seamless loop by
crossfading its tail into its head before writing mono 16-bit WAV.
"""

from __future__ import annotations

import argparse
import wave
from pathlib import Path

import numpy as np


SAMPLE_RATE = 44_100
DURATION_SECONDS = 30.0
SAMPLES = int(SAMPLE_RATE * DURATION_SECONDS)
SEAM_SECONDS = 1.6


def shaped_noise(rng: np.random.Generator, length: int, low: float, high: float,
                 slope: float = 0.0) -> np.ndarray:
    """Create stationary noise with a soft spectral band using an FFT."""
    white = rng.standard_normal(length).astype(np.float32)
    spectrum = np.fft.rfft(white)
    frequencies = np.fft.rfftfreq(length, 1.0 / SAMPLE_RATE)
    gain = np.ones_like(frequencies, dtype=np.float32)

    if low > 0:
        gain *= frequencies / (frequencies + low)
    if high > 0:
        gain *= 1.0 / np.sqrt(1.0 + (frequencies / high) ** 6)
    if slope > 0:
        gain *= 1.0 / np.power(np.maximum(frequencies, 1.0), slope)
        gain /= max(float(np.max(gain)), 1e-6)

    filtered = np.fft.irfft(spectrum * gain, n=length).astype(np.float32)
    filtered -= np.mean(filtered)
    filtered /= max(float(np.std(filtered)), 1e-6)
    return filtered


def slow_curve(rng: np.random.Generator, length: int, points: int,
               low: float = 0.0, high: float = 1.0) -> np.ndarray:
    """Interpolate gently changing control points for natural movement."""
    anchors = np.linspace(0, length - 1, points, dtype=np.float32)
    values = rng.uniform(low, high, points).astype(np.float32)
    curve = np.interp(np.arange(length, dtype=np.float32), anchors, values)
    return curve.astype(np.float32)


def burst(rng: np.random.Generator, length: int, decay: float,
          high_pass: bool = False) -> np.ndarray:
    noise = rng.standard_normal(length).astype(np.float32)
    if high_pass and length > 1:
        noise = np.concatenate(([0.0], np.diff(noise))).astype(np.float32)
        noise /= max(float(np.std(noise)), 1e-6)
    envelope = np.exp(-np.linspace(0.0, decay, length, dtype=np.float32))
    attack = min(max(int(length * 0.05), 1), length)
    envelope[:attack] *= np.linspace(0.0, 1.0, attack, dtype=np.float32)
    return noise * envelope


def add_bursts(signal: np.ndarray, rng: np.random.Generator, count: int,
               min_ms: float, max_ms: float, min_amp: float, max_amp: float,
               decay: float, high_pass: bool = False) -> None:
    for _ in range(count):
        start = int(rng.integers(0, max(1, SAMPLES - int(max_ms * SAMPLE_RATE / 1000))))
        length = int(rng.uniform(min_ms, max_ms) * SAMPLE_RATE / 1000)
        end = min(SAMPLES, start + max(length, 2))
        signal[start:end] += rng.uniform(min_amp, max_amp) * burst(
            rng, end - start, decay, high_pass=high_pass)


def add_rumble(signal: np.ndarray, rng: np.random.Generator, start_seconds: float,
               duration_seconds: float, amplitude: float) -> None:
    start = int(start_seconds * SAMPLE_RATE)
    length = min(int(duration_seconds * SAMPLE_RATE), SAMPLES - start)
    if length <= 0:
        return
    rumble = shaped_noise(rng, length, 18.0, 260.0, slope=0.1)
    time = np.arange(length, dtype=np.float32) / SAMPLE_RATE
    envelope = (1.0 - np.exp(-time / 0.35)) * np.exp(-time / max(duration_seconds * 0.75, 0.1))
    signal[start:start + length] += amplitude * rumble * envelope.astype(np.float32)


def soft_rain(rng: np.random.Generator) -> np.ndarray:
    rain = shaped_noise(rng, SAMPLES, 450.0, 12_000.0)
    body = shaped_noise(rng, SAMPLES, 70.0, 1_000.0)
    movement = 0.78 + 0.22 * slow_curve(rng, SAMPLES, 80)
    signal = rain * movement + body * 0.16
    add_bursts(signal, rng, 470, 12, 95, 0.025, 0.09, 4.6, high_pass=True)
    return signal


def heavy_rain(rng: np.random.Generator) -> np.ndarray:
    rain = shaped_noise(rng, SAMPLES, 650.0, 15_000.0)
    roof = shaped_noise(rng, SAMPLES, 120.0, 2_400.0)
    gusts = 0.74 + 0.35 * slow_curve(rng, SAMPLES, 44)
    signal = rain * gusts + roof * 0.34
    add_bursts(signal, rng, 980, 9, 80, 0.035, 0.13, 5.5, high_pass=True)
    return signal


def ocean_waves(rng: np.random.Generator) -> np.ndarray:
    foam = shaped_noise(rng, SAMPLES, 100.0, 6_500.0)
    deep = shaped_noise(rng, SAMPLES, 22.0, 320.0, slope=0.15)
    time = np.arange(SAMPLES, dtype=np.float32) / SAMPLE_RATE
    tide = 0.18 + 0.82 * np.power(
        (0.5 + 0.5 * np.sin(2.0 * np.pi * time / 8.7 - 0.7)), 2.4)
    tide *= 0.86 + 0.18 * slow_curve(rng, SAMPLES, 32)
    signal = foam * tide + deep * (0.18 + 0.16 * tide)
    for start, duration, amplitude in ((1.2, 4.0, 0.26), (8.9, 4.7, 0.22),
                                       (16.8, 4.1, 0.28), (25.0, 4.2, 0.24)):
        add_rumble(signal, rng, start, duration, amplitude)
    return signal


def thunder(rng: np.random.Generator) -> np.ndarray:
    rain = shaped_noise(rng, SAMPLES, 500.0, 7_000.0) * 0.12
    signal = rain + shaped_noise(rng, SAMPLES, 24.0, 230.0, slope=0.2) * 0.18
    add_rumble(signal, rng, 3.0, 5.2, 0.92)
    add_rumble(signal, rng, 13.7, 4.0, 0.67)
    add_rumble(signal, rng, 23.1, 5.0, 0.82)
    return signal


def stream(rng: np.random.Generator) -> np.ndarray:
    water = shaped_noise(rng, SAMPLES, 230.0, 10_500.0)
    stones = shaped_noise(rng, SAMPLES, 55.0, 1_700.0)
    current = 0.54 + 0.3 * slow_curve(rng, SAMPLES, 110)
    signal = water * current + stones * 0.24
    add_bursts(signal, rng, 300, 20, 130, 0.018, 0.07, 6.8, high_pass=True)
    return signal


def wind(rng: np.random.Generator) -> np.ndarray:
    low = shaped_noise(rng, SAMPLES, 25.0, 280.0, slope=0.1)
    air = shaped_noise(rng, SAMPLES, 180.0, 3_300.0)
    gusts = 0.35 + 0.75 * slow_curve(rng, SAMPLES, 26)
    time = np.arange(SAMPLES, dtype=np.float32) / SAMPLE_RATE
    whistle = np.sin(2.0 * np.pi * (170.0 + 14.0 * np.sin(time / 5.4)) * time).astype(np.float32)
    signal = low * (0.35 + 0.38 * gusts) + air * (0.22 + 0.46 * gusts) + whistle * 0.035 * gusts
    return signal


def campfire(rng: np.random.Generator) -> np.ndarray:
    ember = shaped_noise(rng, SAMPLES, 35.0, 620.0) * 0.34
    air = shaped_noise(rng, SAMPLES, 700.0, 10_000.0) * 0.10
    signal = ember + air
    add_bursts(signal, rng, 160, 18, 80, 0.025, 0.10, 8.2, high_pass=True)
    for start, amplitude in ((2.2, 0.50), (6.7, 0.37), (11.4, 0.62),
                             (17.9, 0.44), (22.8, 0.55), (27.0, 0.39)):
        length = int(rng.uniform(0.12, 0.34) * SAMPLE_RATE)
        end = min(SAMPLES, int(start * SAMPLE_RATE) + length)
        first = int(start * SAMPLE_RATE)
        if end > first:
            signal[first:end] += amplitude * burst(rng, end - first, 7.0, high_pass=True)
    return signal


def make_seamless(signal: np.ndarray) -> np.ndarray:
    """Replace the cyclic join with a 1.6s equal-power crossfade.

    The resulting sequence begins after the original head and ends at the
    original head's last sample, so the next loop continues into its original
    next sample without a hard discontinuity.
    """
    fade = int(SEAM_SECONDS * SAMPLE_RATE)
    head = signal[:fade]
    tail = signal[-fade:]
    phase = np.linspace(0.0, np.pi / 2.0, fade, dtype=np.float32)
    left = np.cos(phase)
    right = np.sin(phase)
    crossfade = tail * left + head * right
    middle = signal[fade:-fade]
    return np.concatenate((middle, crossfade)).astype(np.float32)


def normalize(signal: np.ndarray, target_rms: float) -> np.ndarray:
    signal = signal.astype(np.float32)
    signal -= np.mean(signal)
    signal *= target_rms / max(float(np.sqrt(np.mean(signal * signal))), 1e-6)
    peak = float(np.max(np.abs(signal)))
    if peak > 0.93:
        signal *= 0.93 / peak
    return np.clip(signal, -0.98, 0.98)


def write_wav(path: Path, signal: np.ndarray) -> None:
    pcm = np.rint(signal * 32767.0).astype("<i2")
    with wave.open(str(path), "wb") as output:
        output.setnchannels(1)
        output.setsampwidth(2)
        output.setframerate(SAMPLE_RATE)
        output.writeframes(pcm.tobytes())


def generate(output_dir: Path) -> None:
    output_dir.mkdir(parents=True, exist_ok=True)
    builders = (
        ("soft_rain", soft_rain, 0.17),
        ("heavy_rain", heavy_rain, 0.20),
        ("ocean_waves", ocean_waves, 0.16),
        ("thunder", thunder, 0.13),
        ("stream", stream, 0.16),
        ("wind", wind, 0.14),
        ("campfire", campfire, 0.14),
    )
    for seed, (name, builder, target_rms) in enumerate(builders, start=101):
        rng = np.random.default_rng(seed)
        signal = normalize(make_seamless(builder(rng)), target_rms)
        path = output_dir / f"{name}.wav"
        write_wav(path, signal)
        print(f"{path.name}: {len(signal) / SAMPLE_RATE:.2f}s, {path.stat().st_size / 1024 / 1024:.1f} MiB")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("app/src/main/res/raw"),
        help="directory for generated WAV assets",
    )
    args = parser.parse_args()
    generate(args.output)


if __name__ == "__main__":
    main()
