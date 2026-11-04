import json,sys,unicodedata
from PIL import Image, ImageDraw, ImageFont
MONO="/workspace/work/term/repo/core/ui/src/main/res/font/jetbrains_mono_regular.ttf"
BOLD="/workspace/work/term/repo/core/ui/src/main/res/font/jetbrains_mono_bold.ttf"
CJK="/workspace/work/term/cjk.otf"
S=26
f=ImageFont.truetype(MONO,S); fb=ImageFont.truetype(BOLD,S); fc=ImageFont.truetype(CJK,S); fd=ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",S-2)
cw=f.getbbox("M")[2]; lh=int(S*1.35)
def wide(ch):
    return unicodedata.east_asian_width(ch) in "WF"
for name in sys.argv[1:]:
    d=json.load(open(f"/workspace/work/term/repo/feature/term/build/term-snapshot/{name}.json"))
    cols=d["cols"]; dark=d["theme"]=="dark"
    bg=(27,29,34) if dark else (250,250,250); deffg=(222,224,225) if dark else (59,63,65)
    W=cols*cw+2*cw; H=(len(d["lines"])+1)*lh
    im=Image.new("RGB",(W,H),bg); dr=ImageDraw.Draw(im)
    for r,l in enumerate(d["lines"]):
        y=r*lh+lh//2
        if l["bg"]: dr.rectangle([cw, y, cw+cols*cw, y+lh], fill=l["bg"])
        x=cw
        for sp in l["spans"]:
            col=sp["fg"] or deffg
            for ch in sp["t"]:
                w=2 if wide(ch) else 1
                font=fc if (w==2 or ord(ch)>0x2fff) else (fb if sp["b"] else f)
                if 0x2190<=ord(ch)<=0x2bff and not wide(ch): font=fd
                if ch=="📎": ch="⎘"; font=fd
                dr.text((x, y+(lh-S)//2), ch, font=font, fill=col)
                x+=w*cw
    im.save(f"/workspace/work/term/{name}.png")
    print(name, im.size)
