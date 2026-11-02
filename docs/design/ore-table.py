# The Mines' ore by floor (docs/design/deep-mines.md, "Ore by floor"): vanilla 1.21 overworld ore
# features, mapped onto the 100 floors. Run: python ore-table.py
# Floor 1 = Y 64, floor 100 = Y -64. Weight at a Y = veins per chunk * vein size * height pdf.
# Air exposure is ignored, and so are biome-only ores (emerald, badlands gold,
# dripstone copper) and the large noise veins.

def tri(lo, hi):
    mid, half = (lo + hi) / 2, (hi - lo) / 2
    return lambda y: max(0.0, 1 - abs(y - mid) / half) / half

def uni(lo, hi):
    return lambda y: 1 / (hi - lo + 1) if lo <= y <= hi else 0.0

FEATURES = {
    "Coal":     [(20, 17, tri(0, 192))],
    "Copper":   [(16, 10, tri(-16, 112))],
    "Iron":     [(10, 9, tri(-24, 56)), (10, 4, uni(-64, 72))],
    "Gold":     [(4, 9, tri(-64, 32)), (0.5, 9, uni(-64, -48))],
    "Redstone": [(4, 8, uni(-64, 15)), (8, 8, tri(-96, -32))],
    "Lapis":    [(2, 7, tri(-32, 32)), (4, 7, uni(-64, 64))],
    "Diamond":  [(7, 4, tri(-144, 16)), (1 / 9, 12, tri(-144, 16)), (4, 8, tri(-144, 16)), (2, 8, uni(-64, -4))],
}

def floor_y(f):
    return 64 - (f - 1) * 128 / 99

def mithril(f):
    return 0.0 if f <= 5 else 0.5 + 1.5 * (f - 6) / 94   # 0.5% on floor 6 up to 2% on floor 100

def mix(f):
    y = floor_y(f)
    w = {ore: sum(n * s * pdf(y) for n, s, pdf in fs) for ore, fs in FEATURES.items()}
    total = sum(w.values())
    rest = 100 - mithril(f)
    out = {ore: v / total * rest for ore, v in w.items()}
    out["Mithril"] = mithril(f)
    return y, total, out

base = mix(1)[1]
ores = list(FEATURES) + ["Mithril"]
print("| Floor | ~Y | " + " | ".join(ores) + " |")
print("|---" * (len(ores) + 2) + "|")
for f in [1, 2, 3, 5, 6, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55, 60, 65, 70, 75, 80, 85, 90, 95, 100]:
    y, total, out = mix(f)
    cells = []
    for o in ores:
        v = out[o]
        cells.append("-" if v < 0.05 else f"{v:.1f}" if v < 10 else f"{v:.0f}")
    print(f"| {f} | {round(y)} | " + " | ".join(cells) + f" |   vanilla total vs floor 1: x{total/base:.2f}")
