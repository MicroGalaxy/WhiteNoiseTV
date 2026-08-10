#!/usr/bin/env python3
"""Generate the original, loop-ready ambience beds for White Noise TV.

These are sound-designed ambience loops, not plain white-noise files. Every
bed has a different structure: rain has discrete drops, the sea has recurring
surges, thunder has low-frequency attacks and decays, and the fire has
individual pops. The final PCM loop is crossfaded at its cyclic boundary.
"""

from __future__ import annotations

import argparse
import wave
from pathlib import Path

import numpy as np


SAMPLE_RATE = 44_100
DURATION_SECONDS = 30.0
SAMPLES = int(SAMPLE_RATE * DURATION_SECONDS)
SEAM_SECONDS = 2.0


def band_noise(rng: np.random.Generator, length: int, low: float, high: float,
              pink: float = 0.0) -> np.ndarray:
    """Create normalized noise with a soft, musical spectral band."""
    white = rng.standard_normal(length).astype(np.float32)
    spectrum = np.fft.rfft(white)
    frequencies = np.fft.rfftfreq(length, 1.0 / SAMPLE_RATE)
    gain = np.ones_like(frequencies, dtype=np.float32)

    if low > 0:
        gain *= frequencies / (frequencies + low)
    if high > 0:
        gain *= 1.0 / np.sqrt(1.0 + (frequencies / high) ** 6)
    if pink > 0:
        gain *= 1.0 / np.power(np.maximum(frequencies, 1.0), pink)

    filtered = np.fft.irfft(spectrum * gain, n=length).astype(np.float32)
    filtered -= np.mean(filtered)
    filtered /= max(float(np.std(filtered)), 1e-6)
    return filtered


def smooth_curve(rng: np.random.Generator, length: int, points: int,
                 low: float, high: float) -> np.ndarray:
    anchors = np.linspace(0, length - 1, points, dtype=np.float32)
    values = rng.uniform(low, high, points).astype(np.float32)
    return np.interp(np.arange(length, dtype=np.float32), anchors, values).astype(np.float32)


def shaped_envelope(length: int, attack: float, decay: float) -> np.ndarray:
    time = np.arange(length, dtype=np.float32) / SAMPLE_RATE
    attack_time = max(attack, 1e-4)
    decay_time = max(decay, 1e-4)
    return ((1.0 - np.exp(-time / attack_time)) * np.exp(-time / decay_time)).astype(np.float32)


def add_noise_event(signal: np.ndarray, rng: np.random.Generator, start: float,
                    duration: float, low: float, high: float, amplitude: float,
                    attack: float = 0.01, decay: float | None = None,
                    pink: float = 0.0) -> None:
    first = max(0, int(start * SAMPLE_RATE))
    length = min(int(duration * SAMPLE_RATE), SAMPLES - first)
    if length < 2:
        return
    noise = band_noise(rng, length, low, high, pink=pink)
    event = noise * shaped_envelope(length, attack, decay or duration * 0.7)
    signal[first:first + length] += amplitude * event


def add_tone_event(signal: np.ndarray, start: float, duration: float,
                   amplitude: float, start_hz: float, end_hz: float | None = None,
                   harmonics: tuple[tuple[float, float], ...] = (),
                   attack: float = 0.01, decay: float | None = None) -> None:
    first = max(0, int(start * SAMPLE_RATE))
    length = min(int(duration * SAMPLE_RATE), SAMPLES - first)
    if length < 2:
        return
    time = np.arange(length, dtype=np.float32) / SAMPLE_RATE
    end_frequency = end_hz if end_hz is not None else start_hz
    phase = 2.0 * np.pi * (start_hz * time
                           + 0.5 * (end_frequency - start_hz) / max(duration, 1e-4) * time * time)
    event = np.sin(phase)
    for ratio, level in harmonics:
        event += level * np.sin(phase * ratio)
    event *= shaped_envelope(length, attack, decay or duration * 0.7)
    signal[first:first + length] += amplitude * event.astype(np.float32)


def add_drop(signal: np.ndarray, rng: np.random.Generator, start: float,
             amplitude: float, surface_hz: float) -> None:
    """A tiny impact with a bright tick and a short resonant surface tone."""
    duration = float(rng.uniform(0.035, 0.13))
    add_noise_event(signal, rng, start, duration, 2_000.0, 15_000.0,
                    amplitude * 0.36, attack=0.001, decay=0.035)
    add_tone_event(signal, start, duration * 1.7, amplitude * 0.72,
                   surface_hz * rng.uniform(0.8, 1.2),
                   surface_hz * rng.uniform(0.55, 0.9),
                   harmonics=((2.0, 0.16),), attack=0.001, decay=0.08)


def add_rumble(signal: np.ndarray, rng: np.random.Generator, start: float,
               duration: float, amplitude: float, fundamental: float = 42.0) -> None:
    """Low, moving rumble used for distant thunder and the body of a wave."""
    first = max(0, int(start * SAMPLE_RATE))
    length = min(int(duration * SAMPLE_RATE), SAMPLES - first)
    if length < 2:
        return
    time = np.arange(length, dtype=np.float32) / SAMPLE_RATE
    envelope = shaped_envelope(length, 0.12, duration * 0.7)
    noise = band_noise(rng, length, 16.0, 320.0, pink=0.18)
    phase = 2.0 * np.pi * (fundamental * time - 8.0 * time * time)
    body = np.sin(phase) + 0.38 * np.sin(phase * 1.9 + 0.4)
    signal[first:first + length] += amplitude * envelope * (0.62 * noise + 0.38 * body)


def soft_rain(rng: np.random.Generator) -> np.ndarray:
    """Gentle rain: a soft curtain underneath individually audible drops."""
    curtain = band_noise(rng, SAMPLES, 750.0, 10_500.0)
    low_body = band_noise(rng, SAMPLES, 100.0, 1_200.0, pink=0.1)
    movement = 0.62 + 0.22 * smooth_curve(rng, SAMPLES, 45, 0.0, 1.0)
    signal = curtain * movement * 0.26 + low_body * 0.045

    for _ in range(300):
        add_drop(signal, rng, float(rng.uniform(0.35, 29.25)),
                 float(rng.uniform(0.035, 0.105)), float(rng.uniform(1_900.0, 4_800.0)))
    return signal


def heavy_rain(rng: np.random.Generator) -> np.ndarray:
    """Dense rain with a broad roof-like resonance and larger impacts."""
    curtain = band_noise(rng, SAMPLES, 500.0, 13_000.0)
    roof = band_noise(rng, SAMPLES, 115.0, 2_200.0, pink=0.08)
    gusts = 0.68 + 0.34 * smooth_curve(rng, SAMPLES, 35, 0.0, 1.0)
    signal = curtain * gusts * 0.34 + roof * 0.11

    for _ in range(620):
        add_drop(signal, rng, float(rng.uniform(0.3, 29.15)),
                 float(rng.uniform(0.055, 0.17)), float(rng.uniform(1_300.0, 4_200.0)))
    return signal


def ocean_waves(rng: np.random.Generator) -> np.ndarray:
    """Quiet shore between distinct, slow surf surges."""
    time = np.arange(SAMPLES, dtype=np.float32) / SAMPLE_RATE
    signal = band_noise(rng, SAMPLES, 25.0, 180.0, pink=0.28) * 0.025
    signal += np.sin(2.0 * np.pi * 0.085 * time).astype(np.float32) * 0.018

    # Each wave has a long body, a breaking crest, and a diminishing foam tail.
    wave_starts = (0.9, 7.6, 14.4, 21.5, 27.0)
    for index, start in enumerate(wave_starts):
        duration = float(4.8 + 0.45 * (index % 3))
        length = int(duration * SAMPLE_RATE)
        local_time = np.arange(length, dtype=np.float32) / SAMPLE_RATE
        u = np.clip(local_time / duration, 0.0, 1.0)
        swell = np.sin(np.pi * np.power(u, 0.82)) ** 1.4
        foam = band_noise(rng, length, 180.0, 8_500.0) * swell
        first = int(start * SAMPLE_RATE)
        end = min(SAMPLES, first + length)
        available = end - first
        if available > 1:
            signal[first:end] += foam[:available].astype(np.float32) * 0.17
        add_rumble(signal, rng, start + 0.28, duration * 0.9, 0.20, fundamental=48.0)
        add_noise_event(signal, rng, start + duration * 0.34, 0.95,
                        650.0, 9_000.0, 0.27, attack=0.08, decay=0.48)
        add_tone_event(signal, start + duration * 0.35, 1.45, 0.09,
                       72.0, 43.0, harmonics=((2.0, 0.22),), attack=0.1, decay=0.9)
    return signal


def thunder(rng: np.random.Generator) -> np.ndarray:
    """Three spaced thunder rolls with a quiet, distant rain bed."""
    signal = band_noise(rng, SAMPLES, 650.0, 6_000.0) * 0.018
    signal += band_noise(rng, SAMPLES, 28.0, 190.0, pink=0.2) * 0.025
    for start, duration, amplitude in ((2.8, 5.1, 0.95), (13.2, 4.2, 0.68),
                                       (23.0, 5.2, 0.82)):
        add_noise_event(signal, rng, start, 0.18, 80.0, 1_100.0,
                        amplitude * 0.32, attack=0.003, decay=0.10)
        add_tone_event(signal, start + 0.04, duration, amplitude * 0.25,
                       58.0, 31.0, harmonics=((1.7, 0.35), (2.6, 0.18)),
                       attack=0.12, decay=duration * 0.65)
        add_rumble(signal, rng, start + 0.08, duration, amplitude * 0.82, fundamental=38.0)
        add_rumble(signal, rng, start + 0.72, duration * 0.72, amplitude * 0.33, fundamental=54.0)
    return signal


def stream(rng: np.random.Generator) -> np.ndarray:
    """Continuous bright water with small, high-pitched bubbling splashes."""
    time = np.arange(SAMPLES, dtype=np.float32) / SAMPLE_RATE
    current = 0.58 + 0.24 * smooth_curve(rng, SAMPLES, 90, 0.0, 1.0)
    water = band_noise(rng, SAMPLES, 260.0, 8_800.0)
    stones = band_noise(rng, SAMPLES, 60.0, 1_200.0, pink=0.08)
    signal = water * current * 0.23 + stones * 0.08

    for _ in range(150):
        start = float(rng.uniform(0.3, 29.35))
        duration = float(rng.uniform(0.04, 0.22))
        frequency = float(rng.uniform(1_200.0, 3_800.0))
        add_tone_event(signal, start, duration, float(rng.uniform(0.035, 0.085)),
                       frequency, frequency * rng.uniform(0.75, 1.12),
                       harmonics=((1.9, 0.18),), attack=0.004, decay=duration * 0.55)
    return signal


def wind(rng: np.random.Generator) -> np.ndarray:
    """Breathy air with slow gusts and a faint moving whistle."""
    time = np.arange(SAMPLES, dtype=np.float32) / SAMPLE_RATE
    gusts = 0.22 + 0.95 * smooth_curve(rng, SAMPLES, 22, 0.0, 1.0)
    low = band_noise(rng, SAMPLES, 22.0, 280.0, pink=0.16)
    air = band_noise(rng, SAMPLES, 160.0, 3_200.0)
    signal = low * gusts * 0.14 + air * gusts * 0.21

    frequency = 245.0 + 72.0 * np.sin(2.0 * np.pi * time / 8.7)
    phase = 2.0 * np.pi * np.cumsum(frequency) / SAMPLE_RATE
    signal += np.sin(phase).astype(np.float32) * gusts * 0.075
    for start in (1.8, 8.5, 16.1, 24.2):
        add_noise_event(signal, rng, start, 4.2, 35.0, 2_100.0,
                        0.16, attack=0.8, decay=2.4, pink=0.1)
    return signal


def campfire(rng: np.random.Generator) -> np.ndarray:
    """Warm low flame bed with identifiable snaps, pops, and short hisses."""
    time = np.arange(SAMPLES, dtype=np.float32) / SAMPLE_RATE
    flicker = 0.22 + 0.22 * (0.5 + 0.5 * np.sin(2.0 * np.pi * time / 2.7))
    flame = band_noise(rng, SAMPLES, 55.0, 500.0, pink=0.18)
    air = band_noise(rng, SAMPLES, 800.0, 7_500.0)
    signal = flame * flicker * 0.18 + air * flicker * 0.035

    for _ in range(95):
        start = float(rng.uniform(0.35, 29.2))
        amplitude = float(rng.uniform(0.10, 0.30))
        duration = float(rng.uniform(0.08, 0.24))
        add_noise_event(signal, rng, start, duration, 900.0, 12_000.0,
                        amplitude * 0.30, attack=0.002, decay=duration * 0.55)
        add_tone_event(signal, start, duration * 1.8, amplitude * 0.50,
                       float(rng.uniform(120.0, 280.0)),
                       float(rng.uniform(65.0, 150.0)),
                       harmonics=((2.1, 0.18),), attack=0.002, decay=0.12)

    for start, amplitude in ((2.3, 0.55), (6.6, 0.42), (11.3, 0.68),
                             (17.8, 0.48), (22.7, 0.60), (27.1, 0.45)):
        add_noise_event(signal, rng, start, 0.07, 1_500.0, 14_000.0,
                        amplitude * 0.42, attack=0.001, decay=0.035)
        add_tone_event(signal, start + 0.005, 0.42, amplitude * 0.75,
                       260.0, 78.0, harmonics=((2.0, 0.22),), attack=0.002, decay=0.18)
    return signal


def make_seamless(signal: np.ndarray) -> np.ndarray:
    """Crossfade the tail into the head without a hard cyclic discontinuity."""
    fade = int(SEAM_SECONDS * SAMPLE_RATE)
    head = signal[:fade]
    tail = signal[-fade:]
    phase = np.linspace(0.0, np.pi / 2.0, fade, dtype=np.float32)
    crossfade = tail * np.cos(phase) + head * np.sin(phase)
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
        ("soft_rain", soft_rain, 0.15),
        ("heavy_rain", heavy_rain, 0.18),
        ("ocean_waves", ocean_waves, 0.12),
        ("thunder", thunder, 0.10),
        ("stream", stream, 0.13),
        ("wind", wind, 0.12),
        ("campfire", campfire, 0.11),
    )
    for seed, (name, builder, target_rms) in enumerate(builders, start=201):
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
