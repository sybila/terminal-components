st = TCP > 1.9
et = TCP < 0.01
sg = GLY < 0.01
eg = GLY > 1.9
bd = DCP < 0.5
be = ECH < 0.5

p0 = ((st AU (AF (AG et))) && (sg AU (AF (AG eg))))
p1 = ((st AU (AF (AG et))) && (sg AU (AF (AG eg))) && (AG bd))
p2 = ((st AU (AF (AG et))) && (sg AU (AF (AG eg))) && (AG bd) && (AG be))
