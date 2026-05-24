import wave
import math
import struct
import os

sample_rate = 44100

def generate_sequence(notes, filename):
    with wave.open(filename, 'w') as wav_file:
        wav_file.setnchannels(1)
        wav_file.setsampwidth(2)
        wav_file.setframerate(sample_rate)
        
        for freq, duration in notes:
            num_samples = int(sample_rate * duration)
            for i in range(num_samples):
                t = float(i) / sample_rate
                envelope = math.exp(-t * 12)
                
                # soft square-ish wave for a more 8-bit coin feel, or pure sine for soft chime
                # Let's use pure sine for "suave" (soft)
                sample = math.sin(2 * math.pi * freq * t)
                
                # mix with a bit of harmonics
                sample += 0.2 * math.sin(2 * math.pi * freq * 2 * t)
                
                sample *= envelope * 32767 * 0.25 # very soft volume
                wav_file.writeframes(struct.pack('h', int(sample)))

raw_dir = "/home/nuts11x1/AndroidStudioProjects/Freezy/app/src/main/res/raw"
os.makedirs(raw_dir, exist_ok=True)

# Coin ON: B5 then E6 (ascending)
generate_sequence([(987.77, 0.08), (1318.51, 0.5)], os.path.join(raw_dir, "coin_on.wav"))

# Coin OFF: E6 then B5 (descending)
generate_sequence([(1318.51, 0.08), (987.77, 0.5)], os.path.join(raw_dir, "coin_off.wav"))
print("Done")
