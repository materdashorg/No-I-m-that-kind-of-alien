import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.plaf.basic.BasicScrollBarUI;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Predicate;

/**
 * STATION HELIOS - Border Inspector
 * A "Papers, Please"-style game set on a space colony.
 *
 * Run (Java 11+):   java Main.java
 * Or in VS Code:    install "Extension Pack for Java", open this file, click Run.
 *
 * Keys: A = approve, D = deny, R = read the rules. Applicants arrive on their own.
 *
 * SPRITES (optional): put PNG files in a folder named "sprites" next to this file
 * (the folder you run the game from). Missing files are fine: the game draws
 * built-in placeholder aliens instead.
 *   sprites/zorvian.png      sprites/glimmerkin.png   sprites/thrax.png
 *   sprites/terran.png       sprites/mycelid.png
 *   sprites/booth_bg.png     (optional booth background, stretched to fit)
 * Alien sprites are scaled to fit the booth height, so any size works.
 * Small pixel-art sprites are enlarged with crisp edges. Use transparent backgrounds.
 */
public class Main {

    // ------------------------------------------------------------------ constants
    static final Random RNG = new Random();
    static double PACE = 1.0;               // scales all waiting times (tests shrink it)
    static final int TOTAL_DAYS = 10;
    static final int START_CYCLE = 3140;
    static final int PAY_PER_CORRECT = 10;
    static final int FREE_MISTAKES = 2;      // warnings per day before fines start
    static final int FINE = 5;
    static final int FIRE_LIMIT = 6;         // mistakes in one day = dismissed
    static final int START_CREDITS = 30;
    static final int MAX_MORALE = 5;
    static final int RENT = 20, FOOD = 15, OXYGEN = 10;
    static final int SCAN_DAY = 4;           // day the bio-scan document appears
    static final String KESSEL = "Kessel Prime";
    static final String[] HOMEWORLDS = {"Aldebra IV", "Nym Station", "Orlu Belt", "Vesper", "Tarn Prime", "Cindra"};
    static final String[] PURPOSES = {"Work", "Trade", "Visit", "Research"};
    static final String[] SPEECH = {
            "Good cycle. I'm here for %s.",
            "Please hurry, my shuttle leaves soon. Purpose: %s.",
            "Papers, as requested. I'm here for %s.",
            "Nothing to declare. Just %s.",
            "Here are my documents. Reason for visit: %s."
    };

    // ------------------------------------------------------------------ palette
    static final Color C_BG = new Color(0x070B1A);
    static final Color C_PANEL = new Color(0x101733);
    static final Color C_TEXT = new Color(0xE8EEFF);
    static final Color C_MUTED = new Color(0x8FA0C8);
    static final Color C_CYAN = new Color(0x4FD1E8);
    static final Color C_VIOLET = new Color(0x8B7BFF);
    static final Color C_GREEN = new Color(0x4ADE80);
    static final Color C_RED = new Color(0xFF5C7A);
    static final Color C_AMBER = new Color(0xFFB84D);
    static final Color C_SLATE = new Color(0x39426B);

    // ------------------------------------------------------------------ species
    enum Species {
        ZORVIAN("Zorvian", "ZV", new Color(0x2EC4B6),
                new String[]{"Zy", "Qo", "Vel", "Ixa", "Nor", "Zha"}, new String[]{"ra", "lek", "vin", "thu", "sel", "mor"}),
        GLIMMERKIN("Glimmerkin", "GK", new Color(0xFFD23F),
                new String[]{"Glim", "Shi", "Lum", "Pyr", "Sol", "Fli"}, new String[]{"mi", "ra", "lo", "ki", "ne", "ta"}),
        THRAX("Thrax", "TX", new Color(0xD64545),
                new String[]{"Krag", "Thor", "Brak", "Gor", "Vorn", "Drak"}, new String[]{"ash", "uk", "oth", "gar", "mok", "ul"}),
        TERRAN("Terran", "TR", new Color(0xB8BEC7),
                new String[]{"Alex", "Mara", "Jon", "Elin", "Sam", "Rhea"}, new String[]{"", "son", "ford", "well", "ton", "ley"}),
        MYCELID("Mycelid", "MY", new Color(0x9B59B6),
                new String[]{"Spor", "Myc", "Fun", "Hyph", "Cap", "Mor"}, new String[]{"ia", "ul", "ex", "eth", "ora", "ix"});

        final String label;
        final String code;
        final Color color;
        final String spriteKey;
        private final String[] pre;
        private final String[] suf;

        Species(String label, String code, Color color, String[] pre, String[] suf) {
            this.label = label;
            this.code = code;
            this.color = color;
            this.spriteKey = name().toLowerCase();
            this.pre = pre;
            this.suf = suf;
        }

        String randomName(Random r) {
            String first = pre[r.nextInt(pre.length)] + suf[r.nextInt(suf.length)];
            String last = pre[r.nextInt(pre.length)] + suf[r.nextInt(suf.length)];
            return first + " " + last;
        }
    }

    static Species randomSpecies() {
        Species[] all = Species.values();
        return all[RNG.nextInt(all.length)];
    }

    static String makeId(Species s) {
        return s.code + "-" + (10000 + RNG.nextInt(90000));
    }

    // ------------------------------------------------------------------ applicant + rules
    static class Applicant {
        Species species;            // what they actually look like
        Species passportSpecies;    // what the passport claims
        String passportName, permitName;
        String passportId, permitId;
        String homeworld, purpose, speech;
        int passportExpiry, permitExpiry, today;
        boolean scanInfected;

        List<String> violations(int day) {
            List<String> out = new ArrayList<>();
            for (Rule r : ALL_RULES) {
                if (r.minDay <= day && r.check.test(this)) {
                    out.add(r.violation);
                }
            }
            return out;
        }
    }

    static class Rule {
        final int minDay;
        final String text;
        final String violation;
        final Predicate<Applicant> check;     // true = rule is broken
        final Consumer<Applicant> mutate;     // makes a valid applicant break this rule

        Rule(int minDay, String text, String violation, Predicate<Applicant> check, Consumer<Applicant> mutate) {
            this.minDay = minDay;
            this.text = text;
            this.violation = violation;
            this.check = check;
            this.mutate = mutate;
        }
    }

    static final List<Rule> ALL_RULES = buildRules();

    static List<Rule> buildRules() {
        List<Rule> r = new ArrayList<>();

        r.add(new Rule(1, "Passports must not be expired.",
                "Passport is expired.",
                a -> a.passportExpiry < a.today,
                a -> a.passportExpiry = a.today - 1 - RNG.nextInt(30)));

        r.add(new Rule(1, "Name and ID number on the passport must match the entry permit.",
                "Passport and entry permit do not match (name or ID).",
                a -> !a.passportName.equals(a.permitName) || !a.passportId.equals(a.permitId),
                a -> {
                    if (RNG.nextBoolean()) {
                        String n;
                        do {
                            n = a.species.randomName(RNG);
                        } while (n.equals(a.passportName));
                        a.permitName = n;
                    } else {
                        String id;
                        do {
                            id = makeId(a.species);
                        } while (id.equals(a.passportId));
                        a.permitId = id;
                    }
                }));

        r.add(new Rule(2, "The species on the passport must match the applicant standing at the window.",
                "Passport species does not match the applicant.",
                a -> a.passportSpecies != a.species,
                a -> {
                    Species o;
                    do {
                        o = randomSpecies();
                    } while (o == a.species);
                    a.passportSpecies = o;
                }));

        r.add(new Rule(3, "EMBARGO: no entry for anyone from " + KESSEL + ".",
                "Applicant is from embargoed world " + KESSEL + ".",
                a -> KESSEL.equals(a.homeworld),
                a -> a.homeworld = KESSEL));

        r.add(new Rule(SCAN_DAY, "Bio-scan required. Deny anyone with SPORE BLOOM detected.",
                "Bio-scan shows SPORE BLOOM infection.",
                a -> a.scanInfected,
                a -> a.scanInfected = true));

        r.add(new Rule(5, "Entry permits expire too. Deny expired permits.",
                "Entry permit is expired.",
                a -> a.permitExpiry < a.today,
                a -> a.permitExpiry = a.today - 1 - RNG.nextInt(20)));

        return r;
    }

    static List<Rule> activeRules(int day) {
        List<Rule> out = new ArrayList<>();
        for (Rule r : ALL_RULES) {
            if (r.minDay <= day) {
                out.add(r);
            }
        }
        return out;
    }

    static Applicant generate(int day, int today) {
        Applicant a = new Applicant();
        a.today = today;
        a.species = randomSpecies();
        a.passportSpecies = a.species;
        a.passportName = a.species.randomName(RNG);
        a.permitName = a.passportName;
        a.passportId = makeId(a.species);
        a.permitId = a.passportId;
        a.homeworld = HOMEWORLDS[RNG.nextInt(HOMEWORLDS.length)];
        a.passportExpiry = today + 3 + RNG.nextInt(40);
        a.permitExpiry = today + 1 + RNG.nextInt(15);
        a.purpose = PURPOSES[RNG.nextInt(PURPOSES.length)];
        a.scanInfected = false;
        a.speech = String.format(SPEECH[RNG.nextInt(SPEECH.length)], a.purpose.toLowerCase());

        if (RNG.nextInt(100) < 55) {
            List<Rule> active = activeRules(day);
            Rule first = active.get(RNG.nextInt(active.size()));
            first.mutate.accept(a);
            if (active.size() > 1 && RNG.nextInt(100) < 20) {
                Rule second;
                do {
                    second = active.get(RNG.nextInt(active.size()));
                } while (second == first);
                second.mutate.accept(a);
            }
        }
        return a;
    }

    // ------------------------------------------------------------------ sprites
    static class Sprites {
        private static final Map<String, BufferedImage> CACHE = new HashMap<>();
        private static final String[] DIRS = {"sprites", "src/sprites", "../sprites"};

        static BufferedImage get(String key) {
            if (CACHE.containsKey(key)) {
                return CACHE.get(key);
            }
            BufferedImage img = null;
            for (String dir : DIRS) {
                File f = new File(dir, key + ".png");
                if (f.isFile()) {
                    try {
                        img = ImageIO.read(f);
                    } catch (Exception e) {
                        System.err.println("Could not read sprite " + f + ": " + e.getMessage());
                    }
                    if (img != null) {
                        break;
                    }
                }
            }
            CACHE.put(key, img);   // null is cached too, so we do not hit the disk every frame
            return img;
        }
    }

    // ------------------------------------------------------------------ drawing helpers
    static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    static float easeOutCubic(float t) {
        float u = 1f - t;
        return 1f - u * u * u;
    }

    static Color alpha(Color c, int a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(255, a)));
    }

    static Color mix(Color a, Color b, float t) {
        t = clamp01(t);
        return new Color(
                (int) (a.getRed() + (b.getRed() - a.getRed()) * t),
                (int) (a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                (int) (a.getBlue() + (b.getBlue() - a.getBlue()) * t));
    }

    static void quality(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    }

    static int spacedWidth(FontMetrics fm, String s, float spacing) {
        return (int) (fm.stringWidth(s) + spacing * s.length());
    }

    static void drawSpaced(Graphics2D g, String s, float x, float y, float spacing) {
        FontMetrics fm = g.getFontMetrics();
        float cx = x;
        for (int i = 0; i < s.length(); i++) {
            String c = String.valueOf(s.charAt(i));
            g.drawString(c, cx, y);
            cx += fm.stringWidth(c) + spacing;
        }
    }

    static List<String> wrap(String text, FontMetrics fm, int maxW) {
        List<String> lines = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String word : text.split(" ")) {
            String trial = cur.length() == 0 ? word : cur + " " + word;
            if (fm.stringWidth(trial) > maxW && cur.length() > 0) {
                lines.add(cur.toString());
                cur = new StringBuilder(word);
            } else {
                cur = new StringBuilder(trial);
            }
        }
        if (cur.length() > 0) {
            lines.add(cur.toString());
        }
        return lines;
    }

    static void nebula(Graphics2D g, double cx, double cy, double r, Color c, int a) {
        g.setPaint(new RadialGradientPaint((float) cx, (float) cy, (float) r,
                new float[]{0f, 1f}, new Color[]{alpha(c, a), alpha(c, 0)}));
        g.fill(new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r));
    }

    static String html(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>");
    }

    // ------------------------------------------------------------------ placeholder art
    static void drawEye(Graphics2D g, double cx, double cy, double d) {
        g.setColor(Color.WHITE);
        g.fill(new Ellipse2D.Double(cx - d / 2, cy - d / 2, d, d));
        g.setColor(Color.BLACK);
        g.fill(new Ellipse2D.Double(cx - d / 4, cy - d / 4, d / 2, d / 2));
    }

    /** Draws a simple alien. Occupies x: cx +- ~5u, y: baseY-12u .. baseY (u = figH/12). */
    static void drawPlaceholder(Graphics2D g, Species s, double cx, double baseY, double figH) {
        double u = figH / 12.0;
        Color base = s.color;
        Color dark = base.darker().darker();
        Stroke oldStroke = g.getStroke();

        switch (s) {
            case ZORVIAN: {
                g.setColor(dark);
                g.fill(new RoundRectangle2D.Double(cx - 3.5 * u, baseY - 5 * u, 7 * u, 5 * u, u, u));
                g.setColor(base);
                g.fill(new Ellipse2D.Double(cx - 2.6 * u, baseY - 11 * u, 5.2 * u, 6.8 * u));
                g.setColor(base.darker());
                g.setStroke(new BasicStroke((float) (0.3 * u)));
                g.draw(new Line2D.Double(cx - 1.2 * u, baseY - 10.8 * u, cx - 2.2 * u, baseY - 12 * u));
                g.draw(new Line2D.Double(cx + 1.2 * u, baseY - 10.8 * u, cx + 2.2 * u, baseY - 12 * u));
                g.setStroke(oldStroke);
                drawEye(g, cx - 1.3 * u, baseY - 7.4 * u, 1.1 * u);
                drawEye(g, cx + 1.3 * u, baseY - 7.4 * u, 1.1 * u);
                drawEye(g, cx, baseY - 8.6 * u, 1.1 * u);
                break;
            }
            case GLIMMERKIN: {
                g.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), 70));
                g.fill(new Ellipse2D.Double(cx - 5 * u, baseY - 11 * u, 10 * u, 11 * u));
                g.setColor(base);
                g.fill(new Ellipse2D.Double(cx - 3.5 * u, baseY - 9 * u, 7 * u, 9 * u));
                drawEye(g, cx, baseY - 5.3 * u, 2.6 * u);
                g.setColor(Color.WHITE);
                g.fill(new Ellipse2D.Double(cx - 4.6 * u, baseY - 9.5 * u, 0.5 * u, 0.5 * u));
                g.fill(new Ellipse2D.Double(cx + 4.2 * u, baseY - 7.5 * u, 0.4 * u, 0.4 * u));
                g.fill(new Ellipse2D.Double(cx + 3.0 * u, baseY - 10.5 * u, 0.4 * u, 0.4 * u));
                break;
            }
            case THRAX: {
                g.setColor(dark);
                g.fill(new Rectangle2D.Double(cx - 4 * u, baseY - 5 * u, 8 * u, 5 * u));
                g.setColor(base);
                g.fill(new RoundRectangle2D.Double(cx - 2.5 * u, baseY - 10 * u, 5 * u, 5.2 * u, 0.6 * u, 0.6 * u));
                Path2D.Double hornL = new Path2D.Double();
                hornL.moveTo(cx - 2.3 * u, baseY - 9.6 * u);
                hornL.lineTo(cx - 3.3 * u, baseY - 12 * u);
                hornL.lineTo(cx - 1.2 * u, baseY - 10 * u);
                hornL.closePath();
                Path2D.Double hornR = new Path2D.Double();
                hornR.moveTo(cx + 2.3 * u, baseY - 9.6 * u);
                hornR.lineTo(cx + 3.3 * u, baseY - 12 * u);
                hornR.lineTo(cx + 1.2 * u, baseY - 10 * u);
                hornR.closePath();
                g.setColor(new Color(0xEEE6D2));
                g.fill(hornL);
                g.fill(hornR);
                g.setColor(Color.BLACK);
                g.fill(new Rectangle2D.Double(cx - 2 * u, baseY - 8.4 * u, 4 * u, 1.3 * u));
                g.setColor(new Color(255, 90, 40));
                g.fill(new Rectangle2D.Double(cx - 1.6 * u, baseY - 7.95 * u, 3.2 * u, 0.4 * u));
                break;
            }
            case TERRAN: {
                g.setColor(new Color(0x3C5070));
                g.fill(new RoundRectangle2D.Double(cx - 3.5 * u, baseY - 5 * u, 7 * u, 5 * u, u, u));
                g.setColor(base);
                g.fill(new Ellipse2D.Double(cx - 2 * u, baseY - 10.5 * u, 4 * u, 5.4 * u));
                g.setColor(new Color(0x322A25));
                g.fill(new Arc2D.Double(cx - 2.1 * u, baseY - 10.7 * u, 4.2 * u, 3.6 * u, 0, 180, Arc2D.CHORD));
                g.setColor(Color.BLACK);
                g.fill(new Ellipse2D.Double(cx - 1.1 * u, baseY - 7.9 * u, 0.5 * u, 0.5 * u));
                g.fill(new Ellipse2D.Double(cx + 0.6 * u, baseY - 7.9 * u, 0.5 * u, 0.5 * u));
                g.setStroke(new BasicStroke((float) (0.15 * u)));
                g.draw(new Line2D.Double(cx - 0.7 * u, baseY - 6.3 * u, cx + 0.7 * u, baseY - 6.3 * u));
                g.setStroke(oldStroke);
                break;
            }
            case MYCELID: {
                g.setColor(new Color(0xEDE3D0));
                g.fill(new RoundRectangle2D.Double(cx - 2 * u, baseY - 8.2 * u, 4 * u, 8.2 * u, u, u));
                g.setColor(base);
                g.fill(new Arc2D.Double(cx - 4.8 * u, baseY - 12 * u, 9.6 * u, 8 * u, 0, 180, Arc2D.CHORD));
                g.setColor(new Color(255, 255, 255, 200));
                g.fill(new Ellipse2D.Double(cx - 3 * u, baseY - 10 * u, 1.3 * u, 1.3 * u));
                g.fill(new Ellipse2D.Double(cx + 0.4 * u, baseY - 11 * u, 1.6 * u, 1.6 * u));
                g.fill(new Ellipse2D.Double(cx + 2.4 * u, baseY - 9.4 * u, 1.1 * u, 1.1 * u));
                g.setColor(Color.BLACK);
                g.fill(new Ellipse2D.Double(cx - 1.1 * u, baseY - 6 * u, 0.6 * u, 0.9 * u));
                g.fill(new Ellipse2D.Double(cx + 0.5 * u, baseY - 6 * u, 0.6 * u, 0.9 * u));
                break;
            }
        }
        g.setStroke(oldStroke);
    }

    // ------------------------------------------------------------------ rounded button
    static class RoundButton extends JButton {
        private final Color base;
        private final String hint;
        private String badge;
        private boolean pulse;

        RoundButton(String text, String hint, Color base) {
            super(text);
            this.hint = hint;
            this.base = base;
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setRolloverEnabled(true);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setFont(new Font("SansSerif", Font.BOLD, 15));
            setForeground(Color.WHITE);
            setPreferredSize(new Dimension(230, 50));
        }

        void setBadge(String b) {
            this.badge = b;
            repaint();
        }

        void setPulse(boolean p) {
            this.pulse = p;
            repaint();
        }

        boolean isPulsing() {
            return pulse && isEnabled();
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            quality(g);
            int w = getWidth();
            int h = getHeight();
            boolean on = isEnabled();
            boolean hover = on && getModel().isRollover();
            boolean down = on && getModel().isArmed();
            int m = 4;
            int bw = w - m * 2;
            int bh = h - m * 2;

            if (pulse && on) {
                float ph = (float) (0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 200.0));
                for (int i = 3; i >= 1; i--) {
                    g.setColor(alpha(base.brighter(), (int) ((30 + 70 * ph) / i)));
                    g.fillRoundRect(m - i, m - i, bw + i * 2, bh + i * 2, 20 + i * 2, 20 + i * 2);
                }
            }
            if (on && !down) {
                g.setColor(new Color(0, 0, 0, 90));
                g.fillRoundRect(m, m + 2, bw, bh, 18, 18);
            }
            Color top = on ? base.brighter() : new Color(0x2A3150);
            Color bot = on ? base.darker() : new Color(0x1B2140);
            if (hover) {
                top = mix(top, Color.WHITE, 0.18f);
                bot = mix(bot, Color.WHITE, 0.10f);
            }
            if (down) {
                Color t = top;
                top = bot;
                bot = t;
            }
            int yo = down ? 1 : 0;
            g.setPaint(new GradientPaint(0, m + yo, top, 0, m + bh + yo, bot));
            g.fillRoundRect(m, m + yo, bw, bh, 18, 18);
            g.setColor(alpha(Color.WHITE, on ? 60 : 25));
            g.drawRoundRect(m, m + yo, bw - 1, bh - 1, 18, 18);

            g.setFont(getFont());
            FontMetrics fm = g.getFontMetrics();
            int pill = hint != null ? 34 : 0;
            String t = getText();
            int tw = fm.stringWidth(t);
            int availW = bw - pill;
            int tx = m + Math.max(8, (availW - tw) / 2);
            int ty = m + yo + (bh + fm.getAscent() - fm.getDescent()) / 2;
            g.setColor(on ? Color.WHITE : new Color(0x66708F));
            g.drawString(t, tx, ty);

            if (hint != null) {
                int px = m + bw - 30;
                int py = m + yo + (bh - 22) / 2;
                g.setColor(new Color(0, 0, 0, on ? 70 : 40));
                g.fillRoundRect(px, py, 24, 22, 8, 8);
                g.setFont(new Font("SansSerif", Font.BOLD, 12));
                FontMetrics hm = g.getFontMetrics();
                g.setColor(on ? new Color(0xE8EEFF) : new Color(0x66708F));
                g.drawString(hint, px + (24 - hm.stringWidth(hint)) / 2, py + (22 + hm.getAscent() - hm.getDescent()) / 2);
            }
            if (badge != null && on) {
                g.setFont(new Font("SansSerif", Font.BOLD, 10));
                FontMetrics bm = g.getFontMetrics();
                int bwid = bm.stringWidth(badge) + 12;
                int bx = w - bwid - 2;
                g.setColor(C_AMBER);
                g.fillRoundRect(bx, 0, bwid, 16, 10, 10);
                g.setColor(new Color(0x2A1B00));
                g.drawString(badge, bx + 6, (16 + bm.getAscent() - bm.getDescent()) / 2);
            }
            g.dispose();
        }
    }

    // ------------------------------------------------------------------ HUD bar
    static class HudBar extends JPanel {
        private int day, totalDays, cycle, credits, mistakes, fireLimit, morale, maxMorale, waiting;
        private long flashAt = 0;
        private Color flashColor = C_TEXT;
        private boolean init = false;

        HudBar(JComponent east) {
            setOpaque(false);
            setLayout(new BorderLayout());
            JPanel p = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 9));
            p.setOpaque(false);
            p.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 12));
            p.add(east);
            add(p, BorderLayout.EAST);
            setPreferredSize(new Dimension(100, 62));
        }

        void update(int day, int totalDays, int cycle, int credits, int mistakes, int fireLimit,
                    int morale, int maxMorale, int waiting) {
            if (init && credits != this.credits) {
                flashAt = System.currentTimeMillis();
                flashColor = credits > this.credits ? C_GREEN : C_RED;
            }
            init = true;
            this.day = day;
            this.totalDays = totalDays;
            this.cycle = cycle;
            this.credits = credits;
            this.mistakes = mistakes;
            this.fireLimit = fireLimit;
            this.morale = morale;
            this.maxMorale = maxMorale;
            this.waiting = waiting;
            repaint();
        }

        boolean flashing(long now) {
            return now - flashAt < 800;
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            quality(g);
            int w = getWidth();
            int h = getHeight();
            if (w < 10 || h < 10) {
                g.dispose();
                return;
            }
            g.setColor(alpha(C_PANEL, 235));
            g.fillRoundRect(0, 0, w, h, 22, 22);
            g.setColor(alpha(C_CYAN, 70));
            g.drawRoundRect(0, 0, w - 1, h - 1, 22, 22);

            long now = System.currentTimeMillis();
            Color cc = flashing(now) ? mix(flashColor, C_TEXT, (now - flashAt) / 800f) : C_TEXT;
            int x = 24;
            x = chip(g, x, "DAY", day + " / " + totalDays, C_TEXT);
            x = chip(g, x, "CYCLE", String.valueOf(cycle), C_TEXT);
            x = chip(g, x, "CREDITS", String.valueOf(credits), cc);
            x = pips(g, x, "MISTAKES", mistakes, fireLimit, C_RED);
            x = pips(g, x, "MORALE", morale, maxMorale, C_CYAN);
            chip(g, x, "QUEUE", String.valueOf(waiting), C_TEXT);
            g.dispose();
        }

        private int chip(Graphics2D g, int x, String label, String value, Color vc) {
            Font lf = new Font("SansSerif", Font.BOLD, 11);
            Font vf = new Font("SansSerif", Font.BOLD, 20);
            g.setFont(lf);
            int lw = spacedWidth(g.getFontMetrics(), label, 1.5f);
            g.setFont(vf);
            int vw = g.getFontMetrics().stringWidth(value);
            g.setFont(lf);
            g.setColor(C_MUTED);
            drawSpaced(g, label, x, 25, 1.5f);
            g.setFont(vf);
            g.setColor(vc);
            g.drawString(value, x, 49);
            int nx = x + Math.max(lw, vw) + 28;
            g.setColor(alpha(C_MUTED, 50));
            g.drawLine(nx - 14, 14, nx - 14, getHeight() - 14);
            return nx;
        }

        private int pips(Graphics2D g, int x, String label, int filled, int total, Color on) {
            Font lf = new Font("SansSerif", Font.BOLD, 11);
            g.setFont(lf);
            int lw = spacedWidth(g.getFontMetrics(), label, 1.5f);
            int pw = total * 16;
            g.setColor(C_MUTED);
            drawSpaced(g, label, x, 25, 1.5f);
            for (int i = 0; i < total; i++) {
                int px = x + i * 16;
                if (i < filled) {
                    g.setColor(on);
                    g.fill(new Ellipse2D.Double(px, 36, 11, 11));
                } else {
                    g.setColor(alpha(C_MUTED, 90));
                    g.draw(new Ellipse2D.Double(px + 0.5, 36.5, 10, 10));
                }
            }
            int nx = x + Math.max(lw, pw) + 28;
            g.setColor(alpha(C_MUTED, 50));
            g.drawLine(nx - 14, 14, nx - 14, getHeight() - 14);
            return nx;
        }
    }

    // ------------------------------------------------------------------ animated space view (left panel)
    static class SpaceView extends JPanel {
        private static final int N = 150;
        private int day = 1;
        private int waiting = 0;
        private final float[] sx = new float[N];
        private final float[] sy = new float[N];
        private final float[] sp = new float[N];
        private final float[] sph = new float[N];
        private final int[] ss = new int[N];
        private final double[] bAng = new double[12];
        private final double[] bRad = new double[12];

        SpaceView() {
            setOpaque(false);
            setPreferredSize(new Dimension(270, 100));
            Random r = new Random(11);
            for (int i = 0; i < N; i++) {
                sx[i] = r.nextFloat();
                sy[i] = r.nextFloat();
                sp[i] = 1f + r.nextFloat() * 5f;
                sph[i] = r.nextFloat() * 6.28f;
                ss[i] = r.nextInt(10) == 0 ? 3 : (r.nextBoolean() ? 2 : 1);
            }
            for (int i = 0; i < bAng.length; i++) {
                bAng[i] = r.nextDouble() * Math.PI * 2;
                bRad[i] = 0.25 + r.nextDouble() * 0.65;
            }
        }

        void setState(int day, int waiting) {
            this.day = day;
            this.waiting = waiting;
        }

        private void drawMoon(Graphics2D g, double x, double y, double rr) {
            g.setPaint(new RadialGradientPaint((float) (x - rr * 0.3), (float) (y - rr * 0.3), (float) (rr * 1.6),
                    new float[]{0f, 1f}, new Color[]{new Color(0xF2F4FF), new Color(0x5C6488)}));
            g.fill(new Ellipse2D.Double(x - rr, y - rr, 2 * rr, 2 * rr));
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            quality(g);
            int w = getWidth();
            int h = getHeight();
            if (w < 60 || h < 260) {
                g.dispose();
                return;
            }
            double t = (System.currentTimeMillis() % 3_600_000L) / 1000.0;
            Shape panelShape = new RoundRectangle2D.Float(0, 0, w, h, 26, 26);
            g.setClip(panelShape);

            g.setPaint(new GradientPaint(0, 0, new Color(0x0A1030), 0, h, new Color(0x1B0F3E)));
            g.fillRect(0, 0, w, h);
            nebula(g, w * (0.3 + 0.1 * Math.sin(t * 0.07)), h * 0.28, w * 0.9, new Color(0x6A4FD8), 70);
            nebula(g, w * (0.8 + 0.08 * Math.cos(t * 0.05)), h * 0.65, w * 0.8, new Color(0x1FB5C9), 45);
            nebula(g, w * 0.4, h * 0.92, w * 0.7, new Color(0xD14FA0), 40);

            for (int i = 0; i < N; i++) {
                double x = (sx[i] * w + t * sp[i]) % w;
                double tw = 0.5 + 0.5 * Math.sin(t * (0.6 + sp[i] * 0.05) + sph[i]);
                g.setColor(new Color(255, 255, 255, (int) (70 + 160 * tw)));
                g.fill(new Rectangle2D.Double(x, sy[i] * h, ss[i], ss[i]));
            }

            // shooting star every few seconds
            double ph = (t % 8.0) / 8.0;
            if (ph < 0.1) {
                double p = ph / 0.1;
                double x1 = w * 0.95 - p * w * 0.7;
                double y1 = h * 0.04 + p * h * 0.2;
                double tx = x1 + w * 0.12;
                double ty = y1 - h * 0.03;
                g.setPaint(new GradientPaint((float) tx, (float) ty, alpha(Color.WHITE, 0),
                        (float) x1, (float) y1, alpha(Color.WHITE, 230)));
                g.setStroke(new BasicStroke(2f));
                g.draw(new Line2D.Double(tx, ty, x1, y1));
            }

            // ringed planet with an orbiting moon
            double cx = w / 2.0;
            double cy = h * 0.30;
            double r = w * 0.2;
            double ma = t * 0.45;
            double mx = cx + Math.cos(ma) * r * 2.1;
            double my = cy + Math.sin(ma) * r * 0.5;
            boolean moonBehind = Math.sin(ma) < 0;
            nebula(g, cx, cy, r * 1.8, C_VIOLET, 70);
            if (moonBehind) {
                drawMoon(g, mx, my, r * 0.16);
            }
            AffineTransform old = g.getTransform();
            g.rotate(-0.38, cx, cy);
            g.setStroke(new BasicStroke(7f));
            g.setColor(alpha(new Color(0xD9C8FF), 110));
            g.draw(new Arc2D.Double(cx - r * 1.9, cy - r * 0.45, r * 3.8, r * 0.9, 0, 180, Arc2D.OPEN));
            g.setStroke(new BasicStroke(3f));
            g.setColor(alpha(new Color(0xD9C8FF), 80));
            g.draw(new Arc2D.Double(cx - r * 1.6, cy - r * 0.38, r * 3.2, r * 0.76, 0, 180, Arc2D.OPEN));
            g.setTransform(old);

            g.setPaint(new RadialGradientPaint((float) (cx - r * 0.4), (float) (cy - r * 0.4), (float) (r * 1.6),
                    new float[]{0f, 0.55f, 1f},
                    new Color[]{new Color(0xE3C8FF), new Color(0x7B5BE0), new Color(0x1A1046)}));
            g.fill(new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r));
            Shape oldClip = g.getClip();
            g.clip(new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r));
            for (int k = 0; k < 7; k++) {
                double by = cy - r + (k + 0.5) * (2 * r / 7) + Math.sin(t * 0.3 + k) * 2;
                g.setColor(new Color(255, 255, 255, 12 + (k % 2) * 10));
                g.fill(new Rectangle2D.Double(cx - r, by, 2 * r, r * 0.12));
            }
            g.setPaint(new RadialGradientPaint((float) (cx - r * 0.3), (float) (cy - r * 0.3), (float) (r * 1.5),
                    new float[]{0f, 0.55f, 1f},
                    new Color[]{alpha(Color.BLACK, 0), alpha(Color.BLACK, 0), alpha(Color.BLACK, 170)}));
            g.fill(new Rectangle2D.Double(cx - r, cy - r, 2 * r, 2 * r));
            g.setClip(oldClip);

            g.rotate(-0.38, cx, cy);
            g.setStroke(new BasicStroke(7f));
            g.setColor(alpha(new Color(0xD9C8FF), 150));
            g.draw(new Arc2D.Double(cx - r * 1.9, cy - r * 0.45, r * 3.8, r * 0.9, 180, 180, Arc2D.OPEN));
            g.setStroke(new BasicStroke(3f));
            g.setColor(alpha(new Color(0xD9C8FF), 110));
            g.draw(new Arc2D.Double(cx - r * 1.6, cy - r * 0.38, r * 3.2, r * 0.76, 180, 180, Arc2D.OPEN));
            g.setTransform(old);
            if (!moonBehind) {
                drawMoon(g, mx, my, r * 0.16);
            }

            // title
            g.setStroke(new BasicStroke(1f));
            g.setFont(new Font("SansSerif", Font.BOLD, 15));
            g.setColor(C_TEXT);
            String title = "STATION HELIOS";
            int tw = spacedWidth(g.getFontMetrics(), title, 3f);
            drawSpaced(g, title, (w - tw) / 2f, 34, 3f);
            g.setFont(new Font("SansSerif", Font.BOLD, 11));
            g.setColor(C_CYAN);
            String sub = "BORDER CONTROL";
            drawSpaced(g, sub, (w - spacedWidth(g.getFontMetrics(), sub, 2f)) / 2f, 52, 2f);
            g.setColor(C_MUTED);
            String shift = "SHIFT " + day + " / " + TOTAL_DAYS;
            drawSpaced(g, shift, (w - spacedWidth(g.getFontMetrics(), shift, 2f)) / 2f, 68, 2f);

            // radar: one blip per waiting applicant
            double rcx = w / 2.0;
            double rcy = h - 124;
            double rad = Math.min(w * 0.34, 76);
            g.setPaint(new RadialGradientPaint((float) rcx, (float) rcy, (float) rad,
                    new float[]{0f, 1f},
                    new Color[]{alpha(new Color(0x0F3D4A), 200), alpha(new Color(0x07161E), 230)}));
            g.fill(new Ellipse2D.Double(rcx - rad, rcy - rad, 2 * rad, 2 * rad));
            g.setStroke(new BasicStroke(1f));
            g.setColor(alpha(C_CYAN, 70));
            for (int k = 1; k <= 3; k++) {
                double rr = rad * k / 3.0;
                g.draw(new Ellipse2D.Double(rcx - rr, rcy - rr, 2 * rr, 2 * rr));
            }
            g.draw(new Line2D.Double(rcx - rad, rcy, rcx + rad, rcy));
            g.draw(new Line2D.Double(rcx, rcy - rad, rcx, rcy + rad));
            double sa = (t * 1.5) % (2 * Math.PI);
            g.setColor(alpha(C_CYAN, 55));
            g.fill(new Arc2D.Double(rcx - rad, rcy - rad, 2 * rad, 2 * rad, -Math.toDegrees(sa), 45, Arc2D.PIE));
            g.setColor(alpha(C_CYAN, 200));
            g.setStroke(new BasicStroke(2f));
            g.draw(new Line2D.Double(rcx, rcy, rcx + Math.cos(sa) * rad, rcy + Math.sin(sa) * rad));
            int n = Math.min(waiting, bAng.length);
            for (int i = 0; i < n; i++) {
                double bx = rcx + Math.cos(bAng[i]) * bRad[i] * rad;
                double by = rcy + Math.sin(bAng[i]) * bRad[i] * rad;
                double diff = ((sa - bAng[i]) % (2 * Math.PI) + 2 * Math.PI) % (2 * Math.PI);
                double f = 1 - diff / (2 * Math.PI);
                f = f * f;
                int al = (int) (60 + 195 * f);
                g.setColor(alpha(C_GREEN, al / 3));
                g.fill(new Ellipse2D.Double(bx - 6, by - 6, 12, 12));
                g.setColor(alpha(C_GREEN, al));
                g.fill(new Ellipse2D.Double(bx - 2.5, by - 2.5, 5, 5));
            }
            g.setFont(new Font("SansSerif", Font.BOLD, 12));
            String lbl = waiting == 0 ? "ALL CLEAR" : waiting + " INCOMING";
            g.setColor(waiting == 0 ? C_GREEN : C_TEXT);
            drawSpaced(g, lbl, (w - spacedWidth(g.getFontMetrics(), lbl, 2f)) / 2f, (float) (rcy + rad + 28), 2f);

            g.setClip(null);
            g.setColor(alpha(C_CYAN, 70));
            g.setStroke(new BasicStroke(1f));
            g.drawRoundRect(0, 0, w - 1, h - 1, 26, 26);
            g.dispose();
        }
    }

    // ------------------------------------------------------------------ booth panel (the alien window)
    static class BoothPanel extends JPanel {
        private Applicant applicant;
        private String caption = "";
        private long shownAt;
        private long captionAt;
        private Applicant leaving;
        private long leaveAt;
        private boolean leaveApproved;
        private String stampText;
        private Color stampColor = C_GREEN;
        private long stampAt;
        private String emptyMessage = "";
        private static final int N = 90;
        private final float[] sx = new float[N];
        private final float[] sy = new float[N];
        private final float[] sp = new float[N];
        private final float[] sph = new float[N];
        private final int[] ss = new int[N];

        BoothPanel() {
            setOpaque(false);
            setPreferredSize(new Dimension(700, 340));
            Random r = new Random(7);
            for (int i = 0; i < N; i++) {
                sx[i] = r.nextFloat();
                sy[i] = r.nextFloat() * 0.8f;
                sp[i] = 1f + r.nextFloat() * 6f;
                sph[i] = r.nextFloat() * 6.28f;
                ss[i] = r.nextInt(8) == 0 ? 3 : (r.nextBoolean() ? 2 : 1);
            }
        }

        void showApplicant(Applicant a, String text) {
            long now = System.currentTimeMillis();
            if (a != applicant) {
                shownAt = now;
            }
            applicant = a;
            caption = text == null ? "" : text;
            captionAt = now;
            repaint();
        }

        void showEmpty(String msg) {
            applicant = null;
            emptyMessage = msg == null ? "" : msg;
            repaint();
        }

        void setEmptyMessage(String msg) {
            emptyMessage = msg == null ? "" : msg;
            repaint();
        }

        /** The applicant walks off and a big stamp appears. */
        void dismiss(boolean approved) {
            long now = System.currentTimeMillis();
            if (applicant != null) {
                leaving = applicant;
                leaveAt = now;
                leaveApproved = approved;
            }
            applicant = null;
            stampText = approved ? "APPROVED" : "DENIED";
            stampColor = approved ? C_GREEN : C_RED;
            stampAt = now;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            quality(g);
            int w = getWidth();
            int h = getHeight();
            if (w < 100 || h < 150) {
                g.dispose();
                return;
            }
            long now = System.currentTimeMillis();
            double t = (now % 3_600_000L) / 1000.0;
            g.setClip(new RoundRectangle2D.Float(0, 0, w, h, 24, 24));

            BufferedImage bg = Sprites.get("booth_bg");
            if (bg != null) {
                g.drawImage(bg, 0, 0, w, h, null);
            } else {
                paintDefaultBackground(g, w, h, t);
            }

            int counterH = 74;
            int counterY = h - counterH;
            double cx = w * 0.66;
            double figH = counterY * 0.92;
            double baseY = counterY + 26;

            if (leaving != null) {
                float u = clamp01((now - leaveAt) / 450f);
                if (u >= 1f) {
                    leaving = null;
                } else {
                    double off = (leaveApproved ? 1 : -1) * u * u * w * 0.25;
                    drawFigure(g, leaving, cx + off, baseY, figH, 1f - u);
                }
            }
            if (applicant != null) {
                float e = easeOutCubic(clamp01((now - shownAt) / 420f));
                double off = (1 - e) * w * 0.22;
                drawFigure(g, applicant, cx + off, baseY, figH, e);
            }

            // counter
            g.setPaint(new GradientPaint(0, counterY, new Color(0x1A2142), 0, h, new Color(0x0C1128)));
            g.fillRect(0, counterY, w, counterH);
            g.setColor(alpha(C_CYAN, 150));
            g.fillRect(0, counterY, w, 2);
            g.setColor(alpha(C_CYAN, 30));
            g.fillRect(0, counterY + 2, w, 6);
            g.setFont(new Font("SansSerif", Font.BOLD, 11));
            g.setColor(C_MUTED);
            drawSpaced(g, "WINDOW 07", 24, counterY + 34, 2.5f);
            for (int i = 0; i < 5; i++) {
                boolean lit = ((int) (t * 1.2) + i) % 5 == 0;
                g.setColor(lit ? C_CYAN : alpha(C_MUTED, 60));
                g.fill(new Ellipse2D.Double(26 + i * 14, counterY + 46, 6, 6));
            }

            if (applicant == null) {
                g.setFont(new Font("SansSerif", Font.BOLD, 17));
                g.setColor(alpha(C_TEXT, 210));
                FontMetrics fm = g.getFontMetrics();
                int tw = fm.stringWidth(emptyMessage);
                g.drawString(emptyMessage, Math.max(10, (w - tw) / 2), counterY + counterH / 2 + fm.getAscent() / 2 - 2);
            } else if (!caption.isEmpty()) {
                int reveal = (int) ((now - captionAt) / 14);
                drawBubble(g, caption, 28, 26, Math.max(200, (int) (w * 0.66) - 240), reveal);
            }

            drawStamp(g, now, w * 0.66, counterY * 0.5);

            // CRT scanlines + vignette
            g.setColor(new Color(0, 0, 0, 24));
            for (int y = 0; y < h; y += 3) {
                g.drawLine(0, y, w, y);
            }
            g.setPaint(new RadialGradientPaint(w / 2f, h / 2f, Math.max(w, h) * 0.75f,
                    new float[]{0.55f, 1f}, new Color[]{new Color(0, 0, 0, 0), new Color(0, 0, 0, 150)}));
            g.fillRect(0, 0, w, h);

            g.setClip(null);
            g.setColor(alpha(C_CYAN, 90));
            g.setStroke(new BasicStroke(2f));
            g.drawRoundRect(1, 1, w - 3, h - 3, 24, 24);
            g.dispose();
        }

        private void paintDefaultBackground(Graphics2D g, int w, int h, double t) {
            g.setPaint(new GradientPaint(0, 0, new Color(0x070C24), 0, h, new Color(0x1A1546)));
            g.fillRect(0, 0, w, h);
            nebula(g, w * (0.25 + 0.05 * Math.sin(t * 0.09)), h * 0.35, w * 0.45, new Color(0x6A4FD8), 80);
            nebula(g, w * (0.85 + 0.04 * Math.cos(t * 0.07)), h * 0.2, w * 0.4, new Color(0x1FB5C9), 55);
            nebula(g, w * 0.5, h * 0.9, w * 0.5, new Color(0xD14FA0), 45);
            for (int i = 0; i < N; i++) {
                double x = (sx[i] * w + t * sp[i]) % w;
                double tw = 0.5 + 0.5 * Math.sin(t * (0.7 + sp[i] * 0.05) + sph[i]);
                g.setColor(new Color(255, 255, 255, (int) (80 + 160 * tw)));
                g.fill(new Rectangle2D.Double(x, sy[i] * h, ss[i], ss[i]));
            }
            // small moon peeking in from the left
            double mx = w * 0.13;
            double my = h * 0.5;
            double mr = h * 0.2;
            nebula(g, mx, my, mr * 1.7, new Color(0x4FD1E8), 55);
            g.setPaint(new RadialGradientPaint((float) (mx - mr * 0.35), (float) (my - mr * 0.35), (float) (mr * 1.7),
                    new float[]{0f, 0.6f, 1f},
                    new Color[]{new Color(0xBFE9F5), new Color(0x4F7FB8), new Color(0x142046)}));
            g.fill(new Ellipse2D.Double(mx - mr, my - mr, 2 * mr, 2 * mr));
        }

        private void drawFigure(Graphics2D g, Applicant a, double cx, double baseY, double figH, float opacity) {
            Composite oc = g.getComposite();
            if (opacity < 1f) {
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, clamp01(opacity)));
            }
            // soft glow behind the alien
            nebula(g, cx, baseY - figH * 0.45, figH * 0.65, a.species.color, 38);
            BufferedImage img = Sprites.get(a.species.spriteKey);
            if (img != null) {
                double sc = figH / img.getHeight();
                int iw = Math.max(1, (int) Math.round(img.getWidth() * sc));
                int ih = (int) Math.round(figH);
                Object oldHint = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, sc > 1.0
                        ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
                        : RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(img, (int) Math.round(cx - iw / 2.0), (int) Math.round(baseY - figH), iw, ih, null);
                if (oldHint != null) {
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, oldHint);
                }
            } else {
                drawPlaceholder(g, a.species, cx, baseY, figH);
            }
            g.setComposite(oc);
        }

        private void drawBubble(Graphics2D g, String text, int x, int y, int maxW, int reveal) {
            g.setFont(new Font("SansSerif", Font.PLAIN, 16));
            FontMetrics fm = g.getFontMetrics();
            List<String> lines = wrap(text, fm, maxW);
            int lineH = fm.getHeight();
            int bw = 0;
            for (String l : lines) {
                bw = Math.max(bw, fm.stringWidth(l));
            }
            int pad = 14;
            int boxW = bw + pad * 2;
            int boxH = lines.size() * lineH + pad * 2;
            Color fill = alpha(new Color(0x0C1230), 238);
            g.setColor(fill);
            g.fillRoundRect(x, y, boxW, boxH, 20, 20);
            g.setColor(alpha(C_CYAN, 170));
            g.setStroke(new BasicStroke(1.5f));
            g.drawRoundRect(x, y, boxW, boxH, 20, 20);
            Path2D.Double tail = new Path2D.Double();
            tail.moveTo(x + boxW - 46, y + boxH - 1);
            tail.lineTo(x + boxW - 20, y + boxH - 1);
            tail.lineTo(x + boxW - 6, y + boxH + 16);
            tail.closePath();
            g.setColor(fill);
            g.fill(tail);
            g.setColor(alpha(C_CYAN, 170));
            g.draw(new Line2D.Double(x + boxW - 20, y + boxH - 1, x + boxW - 6, y + boxH + 16));
            g.draw(new Line2D.Double(x + boxW - 6, y + boxH + 16, x + boxW - 46, y + boxH - 1));
            g.setColor(fill);
            g.drawLine(x + boxW - 45, y + boxH, x + boxW - 21, y + boxH);

            int remaining = reveal;
            int ty = y + pad + fm.getAscent();
            g.setColor(C_TEXT);
            for (String l : lines) {
                int n = Math.max(0, Math.min(l.length(), remaining));
                if (n > 0) {
                    g.drawString(l.substring(0, n), x + pad, ty);
                }
                remaining -= l.length() + 1;
                ty += lineH;
            }
        }

        private void drawStamp(Graphics2D g, long now, double sx0, double sy0) {
            if (stampText == null) {
                return;
            }
            long age = now - stampAt;
            if (age >= 1000) {
                return;     // always gone before the next applicant arrives (1100 ms)
            }
            float a = age < 650 ? 1f : 1f - (age - 650) / 350f;
            float pop = age < 140 ? 1.7f - 0.7f * (age / 140f) : 1f;
            AffineTransform old = g.getTransform();
            Stroke oldStroke = g.getStroke();
            g.translate(sx0, sy0);
            g.rotate(-0.16);
            g.scale(pop, pop);
            g.setFont(new Font("SansSerif", Font.BOLD, 54));
            FontMetrics fm = g.getFontMetrics();
            int tw = fm.stringWidth(stampText);
            int base = fm.getAscent() / 2 - 4;
            g.setColor(alpha(stampColor, (int) (235 * a)));
            g.setStroke(new BasicStroke(6f));
            g.drawRoundRect(-tw / 2 - 24, base - fm.getAscent() - 8, tw + 48, fm.getAscent() + fm.getDescent() + 16, 18, 18);
            g.drawString(stampText, -tw / 2, base);
            g.setTransform(old);
            g.setStroke(oldStroke);
        }
    }

    // ------------------------------------------------------------------ document card + desk
    static class DocCard extends JPanel {
        private final String title;
        private final String[][] rows;       // {label, value}
        private final Color[] valueColors;
        private final long born = System.currentTimeMillis();

        DocCard(String title, String[][] rows, Color[] valueColors) {
            this.title = title;
            this.rows = rows;
            this.valueColors = valueColors;
            setOpaque(false);
        }

        boolean animating(long now) {
            return now - born < 450;
        }

        @Override
        public void paint(Graphics g0) {
            long age = System.currentTimeMillis() - born;
            float e = easeOutCubic(clamp01(age / 420f));
            Graphics2D g = (Graphics2D) g0.create();
            g.translate(0, (1 - e) * 12);
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, e));
            super.paint(g);
            g.dispose();
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            quality(g);
            int w = getWidth();
            int h = getHeight() - 12;
            if (w < 60 || h < 60) {
                g.dispose();
                return;
            }
            g.setColor(new Color(0, 0, 0, 100));
            g.fillRoundRect(3, 6, w - 6, h - 2, 16, 16);
            g.setPaint(new GradientPaint(0, 0, new Color(0xF8F0DD), 0, h, new Color(0xE6D9BC)));
            g.fillRoundRect(0, 0, w - 1, h - 1, 16, 16);
            Shape oldClip = g.getClip();
            g.clip(new RoundRectangle2D.Float(0, 0, w - 1, h - 1, 16, 16));
            g.setPaint(new GradientPaint(0, 0, new Color(0x2B3266), 0, 34, new Color(0x1B2048)));
            g.fillRect(0, 0, w, 34);
            g.setClip(oldClip);
            g.setColor(new Color(0x8A7B58));
            g.drawRoundRect(0, 0, w - 1, h - 1, 16, 16);

            g.setFont(new Font("SansSerif", Font.BOLD, 13));
            g.setColor(C_CYAN);
            drawSpaced(g, title, 14, 22, 2f);

            Font lf = new Font("Monospaced", Font.PLAIN, 14);
            Font vf = new Font("Monospaced", Font.BOLD, 14);
            g.setFont(lf);
            int labelW = 0;
            for (String[] r : rows) {
                labelW = Math.max(labelW, g.getFontMetrics().stringWidth(r[0]));
            }
            int y = 34 + 28;
            for (int i = 0; i < rows.length; i++) {
                g.setFont(lf);
                g.setColor(new Color(0x7A6B4A));
                g.drawString(rows[i][0], 14, y);
                g.setFont(vf);
                g.setColor(valueColors[i]);
                g.drawString(rows[i][1], 14 + labelW + 12, y);
                y += 24;
            }
            g.dispose();
        }
    }

    static class DeskPanel extends JPanel {
        DeskPanel() {
            setLayout(new GridLayout(1, 0, 16, 0));
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(14, 16, 6, 16));
            setPreferredSize(new Dimension(100, 206));
        }

        void setCards(List<DocCard> cards) {
            removeAll();
            for (DocCard c : cards) {
                add(c);
            }
            revalidate();
            repaint();
        }

        void clear() {
            removeAll();
            revalidate();
            repaint();
        }

        boolean animating(long now) {
            for (Component c : getComponents()) {
                if (c instanceof DocCard && ((DocCard) c).animating(now)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            quality(g);
            int w = getWidth();
            int h = getHeight();
            g.setColor(alpha(C_PANEL, 235));
            g.fillRoundRect(0, 0, w, h, 22, 22);
            g.setColor(alpha(C_CYAN, 50));
            g.drawRoundRect(0, 0, w - 1, h - 1, 22, 22);
            if (getComponentCount() == 0) {
                g.setFont(new Font("SansSerif", Font.BOLD, 14));
                g.setColor(alpha(C_MUTED, 150));
                String s = "DESK CLEAR";
                drawSpaced(g, s, (w - spacedWidth(g.getFontMetrics(), s, 3f)) / 2f, h / 2f + 5, 3f);
            }
            g.dispose();
        }
    }

    // ------------------------------------------------------------------ status bar
    static class StatusBar extends JPanel {
        private String text = " ";
        private Color accent = C_CYAN;

        StatusBar() {
            setOpaque(false);
            setPreferredSize(new Dimension(100, 58));
        }

        void set(String t, Color accent) {
            this.text = t == null ? " " : t;
            this.accent = accent;
            repaint();
        }

        String getMessage() {
            return text;
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            quality(g);
            int w = getWidth();
            int h = getHeight();
            if (w < 60 || h < 20) {
                g.dispose();
                return;
            }
            g.setColor(alpha(C_PANEL, 235));
            g.fillRoundRect(0, 0, w, h, 18, 18);
            Shape oldClip = g.getClip();
            g.clip(new RoundRectangle2D.Float(0, 0, w, h, 18, 18));
            g.setColor(accent);
            g.fillRect(0, 0, 6, h);
            g.setClip(oldClip);
            g.setColor(alpha(accent, 80));
            g.drawRoundRect(0, 0, w - 1, h - 1, 18, 18);

            g.setFont(new Font("SansSerif", Font.BOLD, 15));
            FontMetrics fm = g.getFontMetrics();
            List<String> lines = wrap(text, fm, w - 48);
            if (lines.size() > 2) {
                lines = new ArrayList<>(lines.subList(0, 2));
                lines.set(1, lines.get(1) + "...");
            }
            int lh = fm.getHeight();
            int y = (h - lines.size() * lh) / 2 + fm.getAscent();
            g.setColor(C_TEXT);
            for (String l : lines) {
                g.drawString(l, 24, y);
                y += lh;
            }
            g.dispose();
        }
    }

    // ------------------------------------------------------------------ rules view pieces
    static class Badge extends JComponent {
        private final String text;
        private final Color color;

        Badge(String text, Color color) {
            this.text = text;
            this.color = color;
            setPreferredSize(new Dimension(32, 32));
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            quality(g);
            int d = Math.min(getWidth(), getHeight()) - 2;
            if (text.isEmpty()) {
                g.setColor(color);
                g.fill(new Ellipse2D.Double(8, 8, d - 14, d - 14));
            } else {
                g.setColor(alpha(color, 50));
                g.fill(new Ellipse2D.Double(1, 1, d, d));
                g.setColor(color);
                g.setStroke(new BasicStroke(1.5f));
                g.draw(new Ellipse2D.Double(1, 1, d, d));
                g.setFont(new Font("SansSerif", Font.BOLD, text.length() > 1 ? 11 : 13));
                FontMetrics fm = g.getFontMetrics();
                g.setColor(C_TEXT);
                g.drawString(text, 1 + (d - fm.stringWidth(text)) / 2, 1 + (d + fm.getAscent() - fm.getDescent()) / 2);
            }
            g.dispose();
        }
    }

    static class Row extends JPanel {
        private final Color border;

        Row(String badgeText, Color badgeColor, String htmlText, Color border) {
            this.border = border;
            setOpaque(false);
            setLayout(new BorderLayout(12, 0));
            setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 14));
            add(new Badge(badgeText, badgeColor), BorderLayout.WEST);
            JLabel l = new JLabel("<html><body style='width:350px'>" + htmlText + "</body></html>");
            l.setFont(new Font("SansSerif", Font.PLAIN, 14));
            l.setForeground(C_TEXT);
            add(l, BorderLayout.CENTER);
            setAlignmentX(Component.LEFT_ALIGNMENT);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            quality(g);
            g.setColor(new Color(0x161E40));
            g.fillRoundRect(0, 0, getWidth(), getHeight(), 16, 16);
            g.setColor(border);
            g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 16, 16);
            g.dispose();
        }
    }

    static class DarkScrollBarUI extends BasicScrollBarUI {
        @Override
        protected void configureScrollBarColors() {
            thumbColor = new Color(0x3A4A86);
            trackColor = new Color(0x0E1430);
        }

        private JButton zero() {
            JButton b = new JButton();
            Dimension d = new Dimension(0, 0);
            b.setPreferredSize(d);
            b.setMinimumSize(d);
            b.setMaximumSize(d);
            return b;
        }

        @Override
        protected JButton createDecreaseButton(int orientation) {
            return zero();
        }

        @Override
        protected JButton createIncreaseButton(int orientation) {
            return zero();
        }

        @Override
        protected void paintThumb(Graphics g, JComponent c, Rectangle r) {
            Graphics2D g2 = (Graphics2D) g.create();
            quality(g2);
            g2.setColor(thumbColor);
            g2.fillRoundRect(r.x + 2, r.y + 2, r.width - 4, r.height - 4, 8, 8);
            g2.dispose();
        }

        @Override
        protected void paintTrack(Graphics g, JComponent c, Rectangle r) {
            g.setColor(trackColor);
            g.fillRect(r.x, r.y, r.width, r.height);
        }
    }

    // ------------------------------------------------------------------ styled dialogs
    static JPanel buildMessagePanel(String headline, String body, Color accent, JComponent extra,
                                    String[] buttons, IntConsumer onPick) {
        JPanel content = new JPanel(new BorderLayout(0, 14)) {
            @Override
            protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create();
                g.setPaint(new GradientPaint(0, 0, new Color(0x131A3A), 0, getHeight(), new Color(0x0A0E24)));
                g.fillRect(0, 0, getWidth(), getHeight());
                g.setColor(accent);
                g.fillRect(0, 0, getWidth(), 4);
                g.dispose();
            }
        };
        content.setBorder(BorderFactory.createEmptyBorder(24, 28, 20, 28));

        JLabel head = new JLabel(headline);
        head.setFont(new Font("SansSerif", Font.BOLD, 20));
        head.setForeground(accent);
        content.add(head, BorderLayout.NORTH);

        JPanel mid = new JPanel();
        mid.setOpaque(false);
        mid.setLayout(new BoxLayout(mid, BoxLayout.Y_AXIS));
        JLabel bodyLbl = new JLabel("<html><body style='width:360px'>" + html(body) + "</body></html>");
        bodyLbl.setFont(new Font("SansSerif", Font.PLAIN, 15));
        bodyLbl.setForeground(C_TEXT);
        bodyLbl.setAlignmentX(Component.LEFT_ALIGNMENT);
        mid.add(bodyLbl);
        if (extra != null) {
            mid.add(Box.createVerticalStrut(12));
            extra.setAlignmentX(Component.LEFT_ALIGNMENT);
            mid.add(extra);
        }
        content.add(mid, BorderLayout.CENTER);

        JPanel row = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 0));
        row.setOpaque(false);
        for (int i = 0; i < buttons.length; i++) {
            final int idx = i;
            RoundButton b = new RoundButton(buttons[i], null, i == 0 ? mix(accent, C_BG, 0.35f) : C_SLATE);
            b.setPreferredSize(new Dimension(160, 46));
            b.addActionListener(e -> onPick.accept(idx));
            row.add(b);
        }
        content.add(row, BorderLayout.SOUTH);
        return content;
    }

    static void closeOnEscape(JDialog dlg) {
        dlg.getRootPane().registerKeyboardAction(e -> dlg.dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
    }

    /** Returns the index of the clicked button, or -1 if the dialog was closed. */
    static int showMessage(Component parent, String title, String headline, String body, Color accent,
                           JComponent extra, String[] buttons) {
        Window owner = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
        JDialog dlg = new JDialog(owner, title, Dialog.ModalityType.APPLICATION_MODAL);
        final int[] result = {-1};
        dlg.setContentPane(buildMessagePanel(headline, body, accent, extra, buttons, i -> {
            result[0] = i;
            dlg.dispose();
        }));
        dlg.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        closeOnEscape(dlg);
        dlg.setResizable(false);
        dlg.pack();
        dlg.setLocationRelativeTo(owner);
        dlg.setVisible(true);
        return result[0];
    }

    static JCheckBox styledCheck(String text) {
        JCheckBox c = new JCheckBox(text, true);
        c.setOpaque(false);
        c.setFocusPainted(false);
        c.setForeground(C_TEXT);
        c.setFont(new Font("SansSerif", Font.PLAIN, 15));
        return c;
    }

    // ------------------------------------------------------------------ the game itself
    static class GamePanel extends JPanel {
        private int day, credits, morale, today;
        private int correctToday, mistakesToday, finesToday, earnedToday;
        private int rulesSeenDay = 0;
        private final Deque<Applicant> queue = new ArrayDeque<>();
        private Applicant current;
        private boolean gameOver;

        private final RoundButton btnApprove = new RoundButton("APPROVE", "A", new Color(0x2FA66A));
        private final RoundButton btnDeny = new RoundButton("DENY", "D", new Color(0xC8415B));
        private final RoundButton btnRules = new RoundButton("RULES", "R", new Color(0x7A5BE0));
        private final HudBar hud = new HudBar(btnRules);
        private final SpaceView space = new SpaceView();
        private final BoothPanel booth = new BoothPanel();
        private final DeskPanel desk = new DeskPanel();
        private final StatusBar statusBar = new StatusBar();
        private final Timer anim;
        private Timer pending;      // the one scheduled action (next applicant, end of shift...)
        private final float[] bgx = new float[80];
        private final float[] bgy = new float[80];
        private final int[] bga = new int[80];
        private final int[] bgs = new int[80];

        GamePanel() {
            setOpaque(true);
            setLayout(new BorderLayout(0, 12));
            setBorder(BorderFactory.createEmptyBorder(14, 16, 16, 16));
            Random r = new Random(3);
            for (int i = 0; i < bgx.length; i++) {
                bgx[i] = r.nextFloat();
                bgy[i] = r.nextFloat();
                bga[i] = 40 + r.nextInt(110);
                bgs[i] = r.nextInt(6) == 0 ? 2 : 1;
            }

            btnRules.setPreferredSize(new Dimension(150, 44));
            btnRules.setToolTipText("Read today's entry rules");
            add(hud, BorderLayout.NORTH);

            JPanel middle = new JPanel(new BorderLayout(14, 0));
            middle.setOpaque(false);
            middle.add(space, BorderLayout.WEST);
            JPanel stage = new JPanel(new BorderLayout(0, 12));
            stage.setOpaque(false);
            stage.add(booth, BorderLayout.CENTER);
            stage.add(desk, BorderLayout.SOUTH);
            middle.add(stage, BorderLayout.CENTER);
            add(middle, BorderLayout.CENTER);

            JPanel south = new JPanel(new BorderLayout(0, 10));
            south.setOpaque(false);
            south.add(statusBar, BorderLayout.NORTH);
            JPanel row = new JPanel(new FlowLayout(FlowLayout.CENTER, 18, 0));
            row.setOpaque(false);
            btnApprove.setPreferredSize(new Dimension(280, 50));
            btnDeny.setPreferredSize(new Dimension(280, 50));
            row.add(btnApprove);
            row.add(btnDeny);
            south.add(row, BorderLayout.CENTER);
            add(south, BorderLayout.SOUTH);

            btnApprove.addActionListener(e -> decide(true));
            btnDeny.addActionListener(e -> decide(false));
            btnRules.addActionListener(e -> showRules());
            bindKey(KeyEvent.VK_A, "approve", btnApprove);
            bindKey(KeyEvent.VK_D, "deny", btnDeny);
            bindKey(KeyEvent.VK_R, "rules", btnRules);

            anim = new Timer(33, e -> tick());
            anim.start();
            newGame();
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            int w = getWidth();
            int h = getHeight();
            g.setPaint(new GradientPaint(0, 0, new Color(0x060A18), 0, h, new Color(0x111A3A)));
            g.fillRect(0, 0, w, h);
            for (int i = 0; i < bgx.length; i++) {
                g.setColor(new Color(255, 255, 255, bga[i]));
                g.fillRect((int) (bgx[i] * w), (int) (bgy[i] * h), bgs[i], bgs[i]);
            }
            g.dispose();
        }

        private void bindKey(int key, String name, JButton b) {
            getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(key, 0), name);
            getActionMap().put(name, new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    if (b.isEnabled()) {
                        b.doClick();
                    }
                }
            });
        }

        private void tick() {
            long now = System.currentTimeMillis();
            booth.repaint();
            space.repaint();
            if (desk.animating(now)) {
                desk.repaint();
            }
            if (btnRules.isPulsing()) {
                btnRules.repaint();
            }
            if (hud.flashing(now)) {
                hud.repaint();
            }
        }

        // ---------------------------------------------------------- game flow
        private void newGame() {
            day = 1;
            credits = START_CREDITS;
            morale = MAX_MORALE;
            rulesSeenDay = 0;
            gameOver = false;
            startDay();
        }

        private void startDay() {
            cancelPending();
            today = START_CYCLE + day - 1;
            correctToday = 0;
            mistakesToday = 0;
            finesToday = 0;
            earnedToday = 0;
            current = null;
            queue.clear();
            int count = Math.min(12, 5 + day);
            for (int i = 0; i < count; i++) {
                queue.add(generate(day, today));
            }
            desk.clear();
            boolean newRule = (day == 1);
            for (Rule r : ALL_RULES) {
                if (r.minDay == day) {
                    newRule = true;
                }
            }
            if (!newRule) {
                rulesSeenDay = day;     // nothing new to read today
            }
            refreshHud();
            updateRulesButton();
            refreshButtons();
            if (newRule) {
                booth.showEmpty("Read today's rules to open the window");
                setStatus(day == 1
                        ? "Welcome, inspector. Open RULES [R] to read the entry rules. Your first applicant arrives right after."
                        : "New rule unlocked today! Open RULES [R]. Your first applicant arrives right after.", C_AMBER);
            } else {
                booth.showEmpty("Day " + day + "  -  " + count + " applicants in line");
                setStatus("Day " + day + " begins. The rules haven't changed. First applicant incoming...", C_CYAN);
                later(1800, this::callNext);
            }
        }

        private void later(int ms, Runnable r) {
            cancelPending();
            pending = new Timer((int) Math.max(1, ms * PACE), e -> r.run());
            pending.setRepeats(false);
            pending.start();
        }

        private void cancelPending() {
            if (pending != null) {
                pending.stop();
                pending = null;
            }
        }

        private boolean hasPending() {
            return pending != null && pending.isRunning();
        }

        private void callNext() {
            if (gameOver || current != null || queue.isEmpty()) {
                return;
            }
            current = queue.poll();
            booth.showApplicant(current, current.speech);
            desk.setCards(buildCards(current));
            refreshHud();
            refreshButtons();
        }

        private void decide(boolean approve) {
            if (gameOver || current == null) {
                return;
            }
            List<String> problems = current.violations(day);
            boolean shouldApprove = problems.isEmpty();
            boolean correct = (approve == shouldApprove);

            if (correct) {
                correctToday++;
                credits += PAY_PER_CORRECT;
                earnedToday += PAY_PER_CORRECT;
                setStatus("Correct! +" + PAY_PER_CORRECT + " credits.", C_GREEN);
            } else {
                mistakesToday++;
                String why = shouldApprove
                        ? "That applicant's papers were all in order."
                        : "Missed: " + String.join(" ", problems);
                if (mistakesToday <= FREE_MISTAKES) {
                    setStatus("WARNING " + mistakesToday + "/" + FREE_MISTAKES + " - " + why, C_AMBER);
                } else {
                    int fine = Math.min(credits, FINE);
                    credits -= fine;
                    earnedToday -= fine;
                    finesToday++;
                    setStatus("CITATION (-" + fine + " credits) - " + why, C_RED);
                }
            }

            current = null;
            booth.dismiss(approve);
            booth.setEmptyMessage(queue.isEmpty() ? "End of shift" : "Next applicant approaching...");
            desk.clear();
            refreshHud();
            refreshButtons();

            if (mistakesToday >= FIRE_LIMIT) {
                later(1300, () -> endGame(
                        "Station Command dismissed you after " + FIRE_LIMIT + " mistakes in a single shift.\n"
                                + "Your household is stranded without an income.", false));
            } else if (queue.isEmpty()) {
                later(1300, this::endDay);
            } else {
                later(correct ? 1100 : 2400, this::callNext);   // more time to read a mistake
            }
        }

        private void endDay() {
            if (gameOver) {
                return;
            }
            String summary = "Correct decisions: " + correctToday + "\n"
                    + "Mistakes: " + mistakesToday + " (" + Math.min(FREE_MISTAKES, mistakesToday)
                    + " warning(s), " + finesToday + " fine(s))\n"
                    + "Net earnings: " + earnedToday + " credits\n"
                    + "Credits on hand: " + credits;
            showInfo(summary, "Shift report - Day " + day);

            handleExpenses();

            if (morale <= 0) {
                endGame("Your household could not survive without proper rent, food and oxygen.\n"
                        + "You lasted until day " + day + ".", false);
                return;
            }
            if (day >= TOTAL_DAYS) {
                endGame("You survived all " + TOTAL_DAYS + " days at the border!\n"
                        + "Final credits: " + credits + "   Household morale: " + morale + "/" + MAX_MORALE
                        + "\n\nStation Helios thanks you for your service.", true);
                return;
            }
            day++;
            startDay();
        }

        private void handleExpenses() {
            while (true) {
                boolean[] pay = chooseExpenses();   // {rent, food, oxygen}
                int total = (pay[0] ? RENT : 0) + (pay[1] ? FOOD : 0) + (pay[2] ? OXYGEN : 0);
                if (total > credits) {
                    showInfo("You only have " + credits + " credits but selected " + total
                            + ".\nPick fewer items.", "Not enough credits");
                    continue;
                }
                credits -= total;
                int unpaid = (pay[0] ? 0 : 1) + (pay[1] ? 0 : 1) + (pay[2] ? 0 : 1);
                if (unpaid == 0) {
                    morale = Math.min(MAX_MORALE, morale + 1);
                } else {
                    morale -= unpaid;
                }
                break;
            }
        }

        private void endGame(String message, boolean won) {
            cancelPending();
            gameOver = true;
            refreshButtons();
            refreshHud();
            if (askPlayAgain(message, won)) {
                newGame();
            } else {
                quit();
            }
        }

        // ---------------------------------------------------------- dialog hooks (overridable for tests)
        void showInfo(String message, String title) {
            showMessage(this, title, title, message, C_CYAN, null, new String[]{"Continue"});
        }

        /** Returns whether to pay {rent, food, oxygen}. Closing the dialog keeps the current ticks. */
        boolean[] chooseExpenses() {
            JCheckBox rent = styledCheck("Rent   (" + RENT + " credits)");
            JCheckBox food = styledCheck("Food   (" + FOOD + " credits)");
            JCheckBox oxy = styledCheck("Oxygen   (" + OXYGEN + " credits)");
            JPanel box = new JPanel(new GridLayout(0, 1, 0, 6));
            box.setOpaque(false);
            box.add(rent);
            box.add(food);
            box.add(oxy);
            showMessage(this, "Household expenses", "Household expenses",
                    "Credits: " + credits + "     Morale: " + morale + "/" + MAX_MORALE
                            + "\nTick what you can pay for tonight. Skipping anything hurts morale.",
                    C_AMBER, box, new String[]{"Pay"});
            return new boolean[]{rent.isSelected(), food.isSelected(), oxy.isSelected()};
        }

        boolean askPlayAgain(String message, boolean won) {
            int r = showMessage(this, won ? "Shift complete" : "Game over", won ? "SHIFT COMPLETE" : "GAME OVER",
                    message, won ? C_GREEN : C_RED, null, new String[]{"Play again", "Quit"});
            return r == 0;
        }

        void quit() {
            System.exit(0);
        }

        void showRules() {
            Window owner = SwingUtilities.getWindowAncestor(this);
            JDialog dlg = new JDialog(owner, "Entry rules", Dialog.ModalityType.APPLICATION_MODAL);
            dlg.setContentPane(buildRulesPanel(dlg::dispose));
            dlg.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            closeOnEscape(dlg);
            Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
            dlg.setSize(600, Math.min(720, screen.height - 100));
            dlg.setLocationRelativeTo(owner);
            dlg.setVisible(true);
            markRulesSeen();
        }

        void markRulesSeen() {
            rulesSeenDay = day;
            updateRulesButton();
            if (current == null && !gameOver && !queue.isEmpty()
                    && correctToday + mistakesToday == 0 && !hasPending()) {
                setStatus("Rules read. Your first applicant is arriving...", C_CYAN);
                booth.setEmptyMessage("First applicant approaching...");
                later(700, this::callNext);
            }
        }

        JPanel buildRulesPanel(Runnable onClose) {
            JPanel root = new JPanel(new BorderLayout(0, 12)) {
                @Override
                protected void paintComponent(Graphics g0) {
                    Graphics2D g = (Graphics2D) g0.create();
                    g.setPaint(new GradientPaint(0, 0, new Color(0x131A3A), 0, getHeight(), new Color(0x0A0E24)));
                    g.fillRect(0, 0, getWidth(), getHeight());
                    g.setColor(C_VIOLET);
                    g.fillRect(0, 0, getWidth(), 4);
                    g.dispose();
                }
            };
            root.setBorder(BorderFactory.createEmptyBorder(22, 24, 18, 24));

            JPanel header = new JPanel();
            header.setOpaque(false);
            header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
            JLabel title = new JLabel("ENTRY RULES");
            title.setFont(new Font("SansSerif", Font.BOLD, 22));
            title.setForeground(C_VIOLET);
            title.setAlignmentX(Component.LEFT_ALIGNMENT);
            JLabel sub = new JLabel("<html><body style='width:500px'>Day " + day + "  |  Cycle " + today
                    + "  -  Approve only if EVERY rule is satisfied. Otherwise deny.</body></html>");
            sub.setFont(new Font("SansSerif", Font.PLAIN, 14));
            sub.setForeground(C_MUTED);
            sub.setAlignmentX(Component.LEFT_ALIGNMENT);
            header.add(title);
            header.add(Box.createVerticalStrut(4));
            header.add(sub);
            root.add(header, BorderLayout.NORTH);

            JPanel list = new JPanel();
            list.setOpaque(false);
            list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
            addSection(list, "STANDING RULES");
            int n = 1;
            for (Rule r : activeRules(day)) {
                boolean fresh = r.minDay == day && day > 1;
                String text = html(r.text) + (fresh ? "  <font color='#FFB84D'><b>NEW</b></font>" : "");
                addRow(list, new Row(String.valueOf(n++), C_CYAN, text, fresh ? C_AMBER : alpha(C_CYAN, 60)));
            }
            addSection(list, "SPECIES GUIDE");
            String[] looks = {"teal, three eyes", "glowing yellow blob, one eye", "red, horned, visor",
                    "humanoid, two eyes, hair", "purple mushroom cap"};
            for (Species s : Species.values()) {
                addRow(list, new Row("", s.color, "<b>" + s.label + "</b> - " + looks[s.ordinal()], alpha(C_MUTED, 50)));
            }
            addSection(list, "PAY AND PENALTIES");
            addRow(list, new Row("+" + PAY_PER_CORRECT, C_GREEN, "credits for every correct decision.", alpha(C_MUTED, 50)));
            addRow(list, new Row(String.valueOf(FREE_MISTAKES), C_AMBER,
                    "free warnings per day. After that every mistake costs " + FINE + " credits.", alpha(C_MUTED, 50)));
            addRow(list, new Row(String.valueOf(FIRE_LIMIT), C_RED,
                    "mistakes in one shift and you are dismissed.", alpha(C_MUTED, 50)));
            addRow(list, new Row("!", C_VIOLET, "Every night you pay rent (" + RENT + "), food (" + FOOD
                    + ") and oxygen (" + OXYGEN + "). Skip them and household morale drops.", alpha(C_MUTED, 50)));

            JScrollPane scroll = new JScrollPane(list);
            scroll.setBorder(null);
            scroll.setOpaque(false);
            scroll.getViewport().setOpaque(false);
            scroll.getVerticalScrollBar().setUnitIncrement(18);
            scroll.getVerticalScrollBar().setUI(new DarkScrollBarUI());
            scroll.getVerticalScrollBar().setPreferredSize(new Dimension(12, 0));
            scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            root.add(scroll, BorderLayout.CENTER);

            RoundButton close = new RoundButton("GOT IT", "Esc", new Color(0x7A5BE0));
            close.setPreferredSize(new Dimension(200, 48));
            close.addActionListener(e -> onClose.run());
            JPanel south = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
            south.setOpaque(false);
            south.add(close);
            root.add(south, BorderLayout.SOUTH);
            return root;
        }

        private void addSection(JPanel list, String title) {
            JLabel l = new JLabel(title);
            l.setFont(new Font("SansSerif", Font.BOLD, 12));
            l.setForeground(C_CYAN);
            l.setAlignmentX(Component.LEFT_ALIGNMENT);
            l.setBorder(BorderFactory.createEmptyBorder(14, 2, 8, 0));
            list.add(l);
        }

        private void addRow(JPanel list, Row row) {
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
            list.add(row);
            list.add(Box.createVerticalStrut(8));
        }

        private List<DocCard> buildCards(Applicant a) {
            List<DocCard> cards = new ArrayList<>();
            Color ink = new Color(0x23233A);
            cards.add(new DocCard("GALACTIC PASSPORT", new String[][]{
                    {"Name", a.passportName},
                    {"ID", a.passportId},
                    {"Species", a.passportSpecies.label},
                    {"Homeworld", a.homeworld},
                    {"Expires", "Cycle " + a.passportExpiry}},
                    new Color[]{ink, ink, ink, ink, ink}));
            cards.add(new DocCard("ENTRY PERMIT", new String[][]{
                    {"Name", a.permitName},
                    {"ID", a.permitId},
                    {"Purpose", a.purpose},
                    {"Valid until", "Cycle " + a.permitExpiry}},
                    new Color[]{ink, ink, ink, ink}));
            if (day >= SCAN_DAY) {
                Color bad = new Color(0xB3203D);
                Color good = new Color(0x1E7A46);
                if (a.scanInfected) {
                    cards.add(new DocCard("BIO-SCAN REPORT", new String[][]{
                            {"Scan date", "Cycle " + a.today},
                            {"Result", "SPORE BLOOM"},
                            {"Status", "DETECTED"}},
                            new Color[]{ink, bad, bad}));
                } else {
                    cards.add(new DocCard("BIO-SCAN REPORT", new String[][]{
                            {"Scan date", "Cycle " + a.today},
                            {"Result", "CLEAR"},
                            {"Status", "NO SPORES"}},
                            new Color[]{ink, good, good}));
                }
            }
            return cards;
        }

        // ---------------------------------------------------------- UI helpers
        private void refreshButtons() {
            boolean live = !gameOver;
            btnApprove.setEnabled(live && current != null);
            btnDeny.setEnabled(live && current != null);
            btnRules.setEnabled(live);
        }

        private void updateRulesButton() {
            boolean unread = rulesSeenDay != day;
            btnRules.setPulse(unread);
            btnRules.setBadge(unread ? "NEW" : null);
        }

        private void refreshHud() {
            hud.update(day, TOTAL_DAYS, today, credits, mistakesToday, FIRE_LIMIT, morale, MAX_MORALE, queue.size());
            space.setState(day, queue.size());
        }

        private void setStatus(String msg, Color accent) {
            statusBar.set(msg, accent);
        }
    }

    // ------------------------------------------------------------------ window
    static class GameWindow extends JFrame {
        GameWindow() {
            super("Station Helios - Border Inspector");
            setDefaultCloseOperation(EXIT_ON_CLOSE);
            setContentPane(new GamePanel());
            Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
            setSize(Math.min(1240, screen.width - 20), Math.min(860, screen.height - 60));
            setMinimumSize(new Dimension(1100, 740));
            setLocationRelativeTo(null);
        }
    }

    // ------------------------------------------------------------------ entry point
    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
        } catch (Exception ignored) {
            // fall back to default look and feel
        }
        SwingUtilities.invokeLater(() -> new GameWindow().setVisible(true));
    }
}
