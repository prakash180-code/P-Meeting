from PIL import Image, ImageFilter, ImageChops

src = Image.open(r"E:\P-Music\P-Meeting\design\p-meeting-background-supplied.jpg").convert("RGB")
print("source", src.size)

# Upscale to the master size; the artwork is a smooth gradient image with no
# fine detail, so a LANCZOS upscale is visually lossless at wallpaper scale.
big = src.resize((1440, 2560), Image.LANCZOS)

# Very light grain so the large gradients do not band on AMOLED at low brightness.
noise = Image.effect_noise((1440, 2560), 6).convert("L")
noise = ImageChops.blend(Image.new("L", (1440, 2560), 128), noise, 0.5)
grain = ImageChops.overlay(big, Image.merge("RGB", (noise, noise, noise)))
big = Image.blend(big, grain, 0.10)

big.save(r"E:\P-Music\P-Meeting\design\p-meeting-background-supplied-1440x2560.png", optimize=True)
big.save(r"E:\P-Music\P-Meeting\design\p-meeting-background-supplied-1440x2560.webp", quality=94, method=6)
big.resize((1080, 1920), Image.LANCZOS).save(
    r"E:\P-Music\P-Meeting\design\p-meeting-background-supplied-1080x1920.jpg",
    quality=92, optimize=True, progressive=True,
)
print("done")
