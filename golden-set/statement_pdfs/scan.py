"""Image-only 'scans': S13 = S02 at 150 dpi, slight skew and noise; S14 = S03 at 100 dpi, blur, 1.5° skew;
H06 = H01 at 200 dpi, 1.0° skew."""
import os
import random

import pypdfium2 as pdfium
from PIL import Image, ImageFilter

here = os.path.dirname(os.path.abspath(__file__))


def scan(src, dst, dpi, skew, blur, noise, seed):
    rnd = random.Random(seed)
    pdf = pdfium.PdfDocument(os.path.join(here, "pdf", src + ".pdf"))
    pages = []
    for page in pdf:
        img = page.render(scale=dpi / 72).to_pil().convert("L")
        img = img.rotate(skew, resample=Image.BICUBIC, expand=False, fillcolor=255)
        if blur:
            img = img.filter(ImageFilter.GaussianBlur(blur))
        px = img.load()
        w, h = img.size
        for _ in range(int(w * h * noise)):
            x, y = rnd.randrange(w), rnd.randrange(h)
            px[x, y] = rnd.choice((0, 90, 160))
        pages.append(img.convert("RGB"))
    pages[0].save(os.path.join(here, "pdf", dst + ".pdf"), save_all=True, append_images=pages[1:], resolution=dpi)


scan("S02", "S13", 150, 0.6, 0, 0.002, 13)
scan("S03", "S14", 100, 1.5, 0.9, 0.006, 14)
scan("H01", "H06", 200, 1.0, 0, 0.001, 61)
print("scans written")
