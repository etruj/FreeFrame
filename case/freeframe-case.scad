// firestand_rev2.scad — Fire 7 (7th gen, 2017) picture-frame case, 3 parts   r2v3
//
// Three parts that print flat with no supports and snap together, after the
// ClawPal slim case layout:
//   bezel()  front frame + side skirt     print FRONT FACE DOWN (smooth bed face)
//   tray()   back plate, tablet rim, clip tabs   print BACK FACE DOWN
//   stand()  hollow wedge with 2 dovetail rails  print on its desk face, as modelled
// Assembly: tablet into the tray (port edge into the plug pocket), press the
// bezel on until the 6 tabs click; slide the stand's rails up into the two
// grooves in the back. The cable leaves through the opening behind the plug
// pocket (which also reaches the power/volume buttons) and drops down beside
// the stand, hidden from the front.
// Cable: the 20 mm pocket is sized for a RIGHT-ANGLE micro-USB plug whose cable
// points toward the tablet's back (or along the edge). A straight plug needs
// ~18 mm rigid + ~8 mm bend and does not fit; one angled toward the screen
// side hits the bezel.
//
// PLA, 0.2 mm layers. Dimension tags: [S] spec, [D] design choice.

/* [Tablet] */
tab_w  = 192.0;  // [S] long edge (landscape width)
tab_h  = 115.0;  // [S] short edge
tab_t  = 9.6;    // [S] thickness
fit    = 0.5;    // [D] in-plane gap per side (device fit, not the 0.1 part-to-part default)
fit_t  = 0.3;    // [D] gap per face in thickness (less rattle than in-plane)

/* [Frame] */
port_side = 1;   // [D] 1 = plug pocket on the right (seen from the front), -1 = left
tilt     = 15;   // [D] lean back from vertical (set by the stand)
lip      = 2.4;  // [D] bezel front plate
skirt    = 2.0;  // [D] bezel side wall (5 lines)
plate    = 4.0;  // [D] tray back plate (holds 2.4 mm dovetail grooves)
rim_t    = 1.6;  // [D] tray rim around the tablet
pocket   = 20;   // [D] room past the port edge for a straight micro-USB plug
side_gap = 0.6;  // [D] rim to skirt on the short sides
border_tb = 14;  // [D] tablet edge to outside, top and bottom
overlap  = 6;    // [D] bezel lip over the bezel of the tablet, every side
open_in  = 2.0;  // [D] back opening reaches this far in behind the tablet's port edge
open_end = 4.0;  // [D] back opening extension stops this far from each end of the edge
corner_r = 8;    // [D] outer corner radius
win_r    = 4;    // [D] window corner radius
ch_front = 1.2;  // [D] 45 deg chamfer, bezel front edge (bed side)
ch_back  = 1.2;  // [D] 45 deg chamfer, tray back edge (bed side)
ch_seam  = 0.6;  // [D] chamfer each side of the bezel/tray seam: a crisp V line
clr      = 0.10; // [D] part-to-part clearance (project default)

/* [Clips] */
tab_len  = 8.0;  // [D] clip tab height off the plate
tab_th   = 1.2;  // [D] clip tab thickness (3 lines); ~1.1 % strain at full flex
tab_wid  = 14;   // [D] clip tab length along the edge
hook     = 0.6;  // [D] hook height (0.5 mm engagement after clearance)
groove_d = 0.8;  // [D] matching V groove in the skirt
tab_xs   = [-70, 0, 70];  // [D] tab positions from centre, top and bottom edges

/* [Stand] */
st_h     = 62;   // [D] stand height up the frame back (global z at the top)
st_d     = 50;   // [D] stand foot depth behind the frame at the desk
st_w     = 130;  // [D] stand width
st_wall  = 2.4;  // [D] wedge shell wall
st_r     = 3;    // [D] wedge long-edge radius
st_ch    = 1.0;  // [D] wedge end chamfer
rail_dx  = 45;   // [D] rails at centre +- this
rail_n   = 8.0;  // [D] dovetail width at the face
rail_w   = 11.0; // [D] dovetail width at the crown
rail_h   = 2.2;  // [D] rail height
rail_z0  = -10;  // [D] rail start vs the frame's bottom edge (local): runs to the
                 //     desk, so its lower end never hangs in the air
rail_len = 42;   // [D] rail length (the top ~30 mm sits in the groove)
rail_c   = 0.15; // [D] per-side sliding clearance (sliding fit, a bit over 0.1)

version  = "r2v3";

// ---------------- derived: frame local coords
// x along the width (tablet 0..tab_w), z up the frame (tablet 0..tab_h),
// y through the thickness, -y = front
cav_y0 = -tab_t / 2 - fit_t;     // cavity front (bezel lip back face)
cav_y1 =  tab_t / 2 + fit_t;     // cavity back  (tray plate front face)
fy0    = cav_y0 - lip;           // front face
fy1    = cav_y1 + plate;         // back face
side_b = fit + pocket + rim_t + side_gap + skirt;
fx0    = -side_b;
fx1    = tab_w + side_b;
fz0    = -border_tb;
fz1    = tab_h + border_tb;
cx     = tab_w / 2;
cz     = tab_h / 2;
// tablet + plug pocket opening inside the rim
cv_x0  = -fit;
cv_x1  = tab_w + fit + pocket;
cv_z0  = -fit;
cv_z1  = tab_h + fit;
// window
win_x0 = overlap;            win_x1 = tab_w - overlap;
win_z0 = overlap;            win_z1 = tab_h - overlap;
// clip tab outer face sits clr inside the skirt
tab_zo = fz1 - skirt - clr;      // top tabs' outer face (bottom mirrored)
hook_y = cav_y1 - tab_len + 1.4; // hook crest (y)

E = 0.2;

// ---------------- primitives
// rounded rect in the x-z plane, inset by d, thickness t along y from y
module xz_rr(x0, x1, z0, z1, r, y, t, d = 0) {
    rr = max(r - d, 0.01);
    translate([0, y + t, 0]) rotate([90, 0, 0]) linear_extrude(t)
        translate([x0 + d + rr, z0 + d + rr])
            offset(r = rr) square([x1 - x0 - 2 * d - 2 * rr, z1 - z0 - 2 * d - 2 * rr]);
}
module outline(y, d, t = 0.01) xz_rr(fx0, fx1, fz0, fz1, corner_r, y, t, d);

// prism along x of a y-z polygon
module yz_prism(x0, x1, pts) {
    translate([x0, 0, 0]) rotate([90, 0, 90]) linear_extrude(x1 - x0) polygon(pts);
}
// prism along z of an x-y polygon
module xy_prism(z0, z1, pts) {
    translate([0, 0, z0]) linear_extrude(z1 - z0) polygon(pts);
}

// ================= BEZEL =================
module bezel() {
    $fa = 2; $fs = 0.4;
    difference() {
        hull() {   // chamfered front edge, chamfered seam edge
            outline(fy0, ch_front);
            outline(fy0 + ch_front, 0);
            outline(cav_y1 - ch_seam - 0.01, 0);
            outline(cav_y1 - 0.01, ch_seam);
        }
        // hollow behind the lip (open at the seam)
        xz_rr(fx0, fx1, fz0, fz1, corner_r, cav_y0, cav_y1 - cav_y0 + 1, skirt);
        // window, 45 deg bevel through the whole lip (opens toward the front)
        hull() {
            xz_rr(win_x0, win_x1, win_z0, win_z1, win_r, cav_y0 + 0.01, 0.01);
            xz_rr(win_x0 - lip - 0.5, win_x1 + lip + 0.5, win_z0 - lip - 0.5, win_z1 + lip + 0.5,
                  win_r + lip + 0.5, fy0 - 0.5, 0.01);
        }
        // V grooves in the skirt for the clip hooks (45 deg flanks both ways)
        for (s = [1, -1]) skirt_groove(s);
        // version, inside the lip in the border (hidden once assembled)
        translate([cx, cav_y0 + E, fz0 + skirt + 4]) rotate([90, 0, 0])
            linear_extrude(0.3 + E) mirror([1, 0, 0])
                text(version, size = 4, halign = "center", valign = "center");
    }
}
module skirt_groove(s) {
    zi = s > 0 ? fz1 - skirt : fz0 + skirt;       // skirt inner face
    g  = groove_d;
    // stops 1 mm short of the corner arcs (ending on the tangent line = coincident edges)
    yz_prism(fx0 + corner_r + 1, fx1 - corner_r - 1,
        [[hook_y - g - 0.2, zi - s * E], [hook_y, zi + s * g], [hook_y + g + 0.2, zi - s * E]]);
}

// ================= TRAY =================
module tray() {
    $fa = 2; $fs = 0.4;
    difference() {
        union() {
            hull() {   // chamfered seam edge, chamfered back edge
                outline(cav_y1, ch_seam);
                outline(cav_y1 + ch_seam, 0);
                outline(fy1 - ch_back, 0);
                outline(fy1 - 0.01, ch_back);
            }
            rim();
            for (s = [1, -1]) for (dx = tab_xs) clip_tab(cx + dx, s);
            stops();
        }
        // cable / button opening behind the plug pocket. Starts open_in behind
        // the tablet's edge so a right-angle plug head (flush on the edge,
        // pointing back) drops straight through it.
        hull() {
            xz_rr(tab_w - open_in, cv_x1 - 1.5, 10, tab_h - 10, 4, cav_y1 - E, 0.01);
            xz_rr(tab_w - open_in - 1.5, cv_x1, 8.5, tab_h - 8.5, 5.5, fy1 + E, 0.01);
        }
        // ...and runs on to open_end from each end beside the corner stops, so
        // a right-angle head whose cable heads for the near corner can still
        // turn out the back (stops keep their full base on the plate)
        hull() {
            xz_rr(tab_w + fit + 2.5, cv_x1 - 1.5, open_end, tab_h - open_end, 3, cav_y1 - E, 0.01);
            xz_rr(tab_w + fit + 1.5, cv_x1, open_end - 1, tab_h - open_end + 1, 4, fy1 + E, 0.01);
        }
        // dovetail grooves, open at the bottom edge
        for (dx = [-rail_dx, rail_dx]) dovetail(cx + dx, rail_c, fz0 - 1, rail_z0 + rail_len + fz0 + 0.3, true);
        // version on the plate's front face, in the hollow border (hidden)
        translate([cx, cav_y1 - E, fz0 + 6]) rotate([-90, 0, 0])
            linear_extrude(0.3 + E) mirror([1, 0, 0])
                text(version, size = 4, halign = "center", valign = "center");
    }
}

// rim around tablet + pocket, from the plate up to just under the bezel lip
module rim() {
    difference() {
        xz_rr(cv_x0 - rim_t, cv_x1 + rim_t, cv_z0 - rim_t, cv_z1 + rim_t, 2 + rim_t,
              cav_y0 + clr, cav_y1 - cav_y0 - clr + E);
        xz_rr(cv_x0, cv_x1, cv_z0, cv_z1, 2, cav_y0 - 1, cav_y1 - cav_y0 + 2);
    }
}

// cantilever clip on the top (s=1) or bottom (s=-1) edge, hook pointing out
module clip_tab(x, s) {
    zo = s > 0 ? tab_zo : fz0 + skirt + clr;      // outer face
    zi = zo - s * tab_th;
    ytip = cav_y1 - tab_len;
    translate([x - tab_wid / 2, 0, 0]) rotate([90, 0, 90]) linear_extrude(tab_wid)
        polygon([[cav_y1 + E, zi], [cav_y1 + E, zo], [hook_y + hook + 0.1, zo],
                 [hook_y, zo + s * hook], [hook_y - hook + 0.3, zo + s * (hook - 0.3)],
                 [ytip, zo - s * 0.3], [ytip, zi]]);
}

// stops at the pocket entrance, top and bottom corners of the port edge
module stops() {
    for (z = [[cv_z0, 8], [tab_h - 8, cv_z1]])
        translate([tab_w + fit, cav_y1 - 3, z[0]]) cube([2, 3 + E, z[1] - z[0]]);
}

// dovetail along local z: narrow at the back face, wide inside the plate.
// c = clearance per side; cut=true makes the groove (0.2 deeper than the rail)
module dovetail(x, c, z0, z1, cut) {
    n = rail_n / 2 + c;
    w = rail_w / 2 + c;
    h = rail_h + (cut ? 0.2 : 0);
    yb = cut ? fy1 + 1 : fy1 + E;                 // back side (open / attach)
    xy_prism(z0, z1, [[x - n, yb], [x - n, fy1], [x - w, fy1 - h],
                      [x + w, fy1 - h], [x + n, fy1], [x + n, yb]]);
}

// ================= STAND =================
// placement of the frame on the desk: rotate top back, back-bottom edge at z=0
function gz(y, z) = -y * sin(tilt) + z * cos(tilt);
function gy(y, z) =  y * cos(tilt) + z * sin(tilt);
z_off = -gz(fy1, fz0);
module place() translate([0, 0, z_off]) rotate([-tilt, 0, 0]) children();
// global y of the local plane y = yl at global height zg
function plane_y(yl, zg) = let(zl = (zg - z_off + yl * sin(tilt)) / cos(tilt)) gy(yl, zl);

st_ym = fy1 + 2 * clr;                       // stand mount face, local y
st_A  = [plane_y(st_ym, 0), 0];
st_B  = [plane_y(st_ym, st_h), st_h];
st_C  = [st_A[0] + st_d, 0];

module st_profile(d) {
    offset(r = max(st_r - d, 0.01)) offset(delta = -st_r)
        polygon([st_A, st_C, st_B]);
}

module stand() {
    $fa = 2; $fs = 0.4;
    x0 = cx - st_w / 2;
    x1 = cx + st_w / 2;
    difference() {
        union() {
            hull() {   // chamfered ends
                yz_slab(x0, st_ch);
                yz_slab(x0 + st_ch, 0);
                yz_slab(x1 - st_ch, 0);
                yz_slab(x1, st_ch);
            }
            // rails on the mount face (local coords), clipped to the stand height
            intersection() {
                place() for (dx = [-rail_dx, rail_dx])
                    dovetail_rail(cx + dx);
                translate([x0, -100, 0]) cube([st_w, 300, st_h - 3]);
            }
        }
        // hollow, open underneath (roof faces >= 45 deg: no support; one body)
        translate([x0 + st_wall, 0, 0]) rotate([90, 0, 90])
            linear_extrude(st_w - 2 * st_wall)
                hull() {   // inner triangle, run down through the desk face
                    offset(delta = -st_wall) polygon([st_A, st_C, st_B]);
                    translate([0, -5]) offset(delta = -st_wall) polygon([st_A, st_C, st_B]);
                }
        // version inside the end wall, read from underneath
        translate([x0 + st_wall - 0.3, (st_A[0] + st_C[0]) / 2 + 2, 8]) rotate([90, 0, 90])
            linear_extrude(0.3 + E) text(version, size = 4, halign = "center", valign = "center");
    }
}
module yz_slab(x, d) {
    translate([x, 0, 0]) rotate([90, 0, 90]) linear_extrude(0.01) st_profile(d);
}
module dovetail_rail(x) {
    // the rail sits on the stand face (st_ym) and reaches into the groove
    n = rail_n / 2; w = rail_w / 2;
    xy_prism(fz0 + rail_z0, fz0 + rail_z0 + rail_len,
        [[x - n, st_ym + E + 1], [x - n, fy1], [x - w, fy1 - rail_h],
         [x + w, fy1 - rail_h], [x + n, fy1], [x + n, st_ym + E + 1]]);
}

// ================= views / exports =================
module sided() {
    if (port_side > 0) children();
    else translate([tab_w, 0, 0]) mirror([1, 0, 0]) children();
}
module bezel_print() sided() translate([0, 0, -fy0]) rotate([90, 0, 0]) bezel();
module tray_print()  sided() translate([0, 0, fy1]) rotate([-90, 0, 0]) tray();
module stand_print() stand();

// ---- fit-test pieces: the plug end of the bezel and tray, real geometry,
// cut at test_x0 (includes the plug pocket, back opening, corner stops and the
// top + bottom clips at cx + 70). Print both, snap together on the tablet.
test_x0 = cx + 70 - tab_wid / 2 - 6;   // 6 mm left of the end clip
module test_cut() translate([test_x0, -50, -50]) cube([300, 100, 300]);
module bezel_test_print() sided() translate([0, 0, -fy0]) rotate([90, 0, 0])
    intersection() { bezel(); test_cut(); }
module tray_test_print() sided() translate([0, 0, fy1]) rotate([-90, 0, 0])
    intersection() { tray(); test_cut(); }

module assembly() {
    sided() {
        place() {
            color("gainsboro") bezel();
            color("silver") tray();
            %translate([0, -tab_t / 2, 0]) cube([tab_w, tab_t, tab_h]);
        }
        color("lightsteelblue") stand();
    }
}

assembly();
