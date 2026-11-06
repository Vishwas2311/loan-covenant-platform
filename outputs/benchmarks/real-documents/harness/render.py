import sys, pdfplumber
for d in sys.argv[1:]:
    with pdfplumber.open(f"stmt_pages/{d}.pdf") as pdf:
        for i, p in enumerate(pdf.pages):
            p.to_image(resolution=110).save(f"adj2/{d}_{i+1}.png")
