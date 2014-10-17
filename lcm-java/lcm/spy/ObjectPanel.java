package lcm.spy;

import javax.swing.*;
import javax.swing.event.*;
import javax.swing.table.*;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.Point;

import java.io.*;
import java.util.*;
import java.util.Map.Entry;
import java.util.jar.*;
import java.util.zip.*;

import java.lang.reflect.*;

import lcm.spy.ObjectPanel.SparklineData;

import info.monitorenter.gui.chart.Chart2D;
import info.monitorenter.gui.chart.ITrace2D;
import info.monitorenter.gui.chart.ITracePoint2D;
import info.monitorenter.gui.chart.ZoomableChart;
import info.monitorenter.gui.chart.axis.AxisLinear;
import info.monitorenter.gui.chart.controls.LayoutFactory;
import info.monitorenter.gui.chart.rangepolicies.RangePolicyMinimumViewport;
import info.monitorenter.gui.chart.traces.Trace2DLtd;
import info.monitorenter.gui.chart.traces.painters.TracePainterDisc;
import info.monitorenter.gui.chart.views.ChartPanel;
import info.monitorenter.util.Range;

/**
 * Panel that displays general data for lcm types.  Viewed by double-clicking
 * or right-clicking and selecting Structure Viewer on the channel list.
 *
 */
public class ObjectPanel extends JPanel
{
    String name;
    Object o;
    long utime; // time of this message's arrival
    int lastwidth = 500;
    int lastheight = 100;
    boolean updateGraphs = false;
    JViewport scrollViewport;

    final int sparklineWidth = 150; // width in pixels of all sparklines
    
    // margin around the viewport area in which we will draw graphs
    // (in pixels)
    final int sparklineDrawMargin = 500;

    Section currentlyHoveringSection; // section the mouse is hovering over
    String currentlyHoveringName; // name of the section the mouse is hovering over

    ChartData chartData; // global data about all charts being displayed by lcm-spy

    // array of all sparklines that are visible 
    // or near visible to the user right now
    ArrayList<SparklineData> visibleSparklines = new ArrayList<SparklineData>();

    class Section
    {
        int x0, y0, x1, y1; // bounding coordinates for sensitive area
        boolean collapsed;
        HashMap<String, SparklineData> sparklines;


        public Section()
        {
            sparklines = new HashMap<String, SparklineData>();
        }
    }

    /**
     * Data about an individual sparkline.
     *
     */
    class SparklineData
    {
        int xmin, xmax;
        int ymin, ymax;
        boolean isHovering;

        // all sparklines have a chart associated with them, even though
        // we do not use it for display. This allows us to use the data-collection
        // and management features
        Chart2D chart;

        String name;
    }

    ArrayList<Section> sections = new ArrayList<Section>();

    /**
     * Constructor for an object panel, call when the user clicks to see more
     * data about a message.
     *
     * @param name name of the channel
     * @param chartData global data about all charts displayed by lcm-spy
     */
    public ObjectPanel(String name, ChartData chartData)
    {
        this.name = name;
        this.setLayout(null); // not using a layout manager, drawing everything ourselves
        this.chartData = chartData;

        addMouseListener(new MyMouseAdapter());

        addMouseMotionListener(new MyMouseMotionListener());
        
    }
    
    /**
     * If given a viewport, the object panel can make smart decisions to
     * not draw graphs that are currently outside of the user's view
     * 
     * @param viewport viewport from the JScrollPane that contains this ObjectPanel.
     */
    public void setViewport(JViewport viewport) {
        scrollViewport = viewport;
        
        scrollViewport.addChangeListener(new MyViewportChangeListener());
    }

    /**
     * Called on mouse movement to determine if we need to
     * highlight a line or open a chart.
     *
     * @param e MouseEvent to process
     *
     * @return returns true if a mouse click was consumed
     */
    public boolean doSparklineInteraction(MouseEvent e)
    {
        int y = e.getY();
        for (int i = sections.size() -1; i > -1; i--)
        {
            Section section = sections.get(i);

            if (section.y0 < y && section.y1 > y)
            {
                // we might be hovering over something in this section

                Iterator<Entry<String, SparklineData>> it = section.sparklines.entrySet().iterator();
                while (it.hasNext())
                {
                    Entry<String, SparklineData> pair = it.next();

                    SparklineData data = pair.getValue();
                    if (data.ymin < y && data.ymax > y && section.collapsed == false)
                    {
                        // the mouse is above this sparkline
                        currentlyHoveringSection = section;
                        currentlyHoveringName = pair.getKey();

                        if (e.getButton() == MouseEvent.BUTTON1)
                        {
                            displayDetailedChart(data, false, false);
                        } else if (e.getButton() == MouseEvent.BUTTON2)
                        {
                            // middle click means open a new chart
                            displayDetailedChart(data, true, true);
                        } else if (e.getButton() == MouseEvent.BUTTON3)
                        {
                            // right click means same chart, new axis
                            displayDetailedChart(data, false, true);
                        }

                        return true;
                    }
                }
            }
        }
        currentlyHoveringSection = null;
        return false;
    }

    /**
     * Opens a detailed, interactive chart for a data stream.  If the data is already
     * displayed in a chart, brings that chart to the front instead.
     *
     *
     * @param data data channel to display
     * @param openNewChart set to true to force opening of a new chart window, false to add
     *      to an already-open chart (if one exists)
     * @param newAxis true if we should add a new Y-axis to display this data
     */
    public void displayDetailedChart(SparklineData data, boolean openNewChart, boolean newAxis)
    {
        // check to see if we are already displaying this trace
        Trace2DLtd trace = (Trace2DLtd) data.chart.getTraces().first();

        for (ZoomableChartScrollWheel chart : chartData.getCharts())
        {
            if (chart.getTraces().contains(trace))
            {
                chart.toFront();
                return;
            }
        }


        if (openNewChart || chartData.getCharts().size() < 1)
        {
            trace.setMaxSize(chartData.detailedSparklineChartSize);
            ZoomableChartScrollWheel.newChartFrame(chartData, trace);
        } else
        {
            // find the most recently interacted with chart

            long bestFocusTime = -1;
            ZoomableChartScrollWheel bestChart = null;

            for (ZoomableChartScrollWheel chart : chartData.getCharts())
            {
                if (chart.getLastFocusTime() > bestFocusTime)
                {
                    bestFocusTime = chart.getLastFocusTime();
                    bestChart = chart;
                }

            }

            if (bestChart != null)
            {
               // add this trace to the winning chart

                if (!bestChart.getTraces().contains(trace))
                {
                    trace.setMaxSize(chartData.detailedSparklineChartSize);
                    trace.setColor(bestChart.popColor());

                    if (newAxis)
                    {
                        // add an axis
                        AxisLinear axis = new AxisLinear();
                        bestChart.addAxisYRight(axis);
                        bestChart.addTrace(trace, bestChart.getAxisX(), axis);
                    } else
                    {
                        bestChart.addTrace(trace);
                    }


                }
                bestChart.updateRightClickMenu();
                bestChart.toFront();

            }

        }
    }
    
    class PaintState
    {
        Color indentColors[] = new Color[] {new Color(255,255,255), new Color(230,230,255), new Color(200,200,255)};
        Graphics g;
        FontMetrics fm;
        JPanel panel;

        int indent_level;
        int color_level;
        int y;
        int textheight;

        int x[] = new int[4]; // tab stops
        int indentpx = 20; // pixels per indent level

        int maxwidth;

        int nextsection = 0;

        int collapse_depth = 0;

        public int beginSection(String type, String name, String value)
        {
            // allocate a new section number and make sure there's
            // an entry for us to use in the sections array.
            int section = nextsection++;
            Section cs;
            if (section == sections.size()) {
                cs = new Section();
                sections.add(cs);
            }

            cs = sections.get(section);

            // Some enclosing section is collapsed, exit before drawing anything.

            if (collapse_depth == 0)
            {
                // we're not currently collapsed. Draw the header (at least.)
                beginColorBlock();
                spacer();

                Font of = g.getFont();
                g.setFont(of.deriveFont(Font.BOLD));
                FontMetrics fm = g.getFontMetrics();

                String tok = cs.collapsed ? "+" : "-";
                g.setColor(Color.white);
                g.fillRect(x[0] + indent_level*indentpx, y, 1, 1);
                g.setColor(Color.black);

                String type_split[] = type.split("\\.");
                String drawtype = type_split[type_split.length - 1];

                int type_len = fm.stringWidth(drawtype);
                int name_len = fm.stringWidth(name);

                int tok_pixidx = x[0] + indent_level*indentpx;
                int type_pixidx = x[0] + indent_level*indentpx + 10;

                g.drawString(tok, tok_pixidx, y);
                g.drawString(drawtype, type_pixidx, y);

                // check if type field is too long. put name on new line if yes
                if (type_pixidx + type_len > x[1])
                    y+= textheight;
                g.drawString(name,  x[1], y);

                // check if name field is too long.  put value on new line if yes
                // No need to put it on a new line if value is NULL
                if (x[1] + name_len > x[2] && value.length() > 0)
                    y+= textheight;
                g.drawString(value, x[2], y);

                g.setFont(of);

                // set up the coordinates where clicking will toggle whether
                // we are collapsed.
                cs.x0 = x[0];
                cs.x1 = getWidth();
                cs.y0 = y - textheight;
                cs.y1 = y;

                y += textheight;

                indent();
            }
            else
            {
                // no clicking area.
                cs.x0 = 0; cs.x1 = 0; cs.y0 = 0; cs.y1 = 0;
            }


            // if this section is collapsed, stop drawing.
            if (sections.get(section).collapsed)
                collapse_depth ++;

            return section;
        }

        public void endSection(int section)
        {
            Section cs = sections.get(section);
            cs.y1 = y;

            // if this section is collapsed, resume drawing.
            if (sections.get(section).collapsed)
                collapse_depth --;

            unindent();
            spacer();
            endColorBlock();
            spacer();
        }

        public void drawStrings(String type, String name, String value, boolean isstatic)
        {
            if (collapse_depth > 0)
                return;

            Font of = g.getFont();
            if (isstatic)
                g.setFont(of.deriveFont(Font.ITALIC));

            g.drawString(type,  x[0] + indent_level*indentpx, y);
            g.drawString(name,  x[1], y);
            g.drawString(value, x[2], y);

            y+= textheight;

            g.setFont(of);
        }

        /**
         * Draws a row for a piece of data in the message and also a sparkline
         * for that data.
         *
         * @param cls type of the data
         * @param name name of the entry in the message
         * @param o the data itself
         * @param isstatic true if the data is static
         * @param sec index of section this row is in, used to determine if this
         *      row should be highlighted because it is under the mouse cursor.
         */
        public void drawStringsAndGraph(Class cls, String name, Object o, boolean isstatic,
                int sec)
        {
            if (collapse_depth > 0)
                return;

            if (isstatic)
            {
                drawStrings(cls.getName(), name, o.toString(), isstatic);
                return;
            }
            Color oldColor = g.getColor();

            boolean isHovering = false;
            Section cs = sections.get(sec);
            if (currentlyHoveringSection != null && cs == currentlyHoveringSection
                    && currentlyHoveringName.equals(name))
            {
                isHovering = true;
                g.setColor(Color.RED);
            }


            Font of = g.getFont();

            g.drawString(cls.getName(),  x[0] + indent_level*indentpx, y);
            g.drawString(name,  x[1], y);
            
            
            if (cls.equals(Byte.TYPE)) {
                g.drawString(String.format("0x%02X   %03d   %+04d   %c",
                        ((Byte)o),((Byte)o).intValue()&0x00FF,((Byte)o), ((Byte)o)&0xff), x[2], y);
            } else {
                g.drawString(o.toString(), x[2], y);
            }

            g.setColor(oldColor);

            // draw the graph
            double value = Double.NaN;

            if (o instanceof Double)
                value = (Double) o;
            else if (o instanceof Float)
                value = (Float) o;
            else if (o instanceof Integer)
                value = (Integer) o;
            else if (o instanceof Long)
                value = (Long) o;
            else if (o instanceof Byte)
                value = (Byte) o;

            if (!Double.isNaN(value))
            {
                // see if we already have a sparkline for this item

                SparklineData data = cs.sparklines.get(name);
                Chart2D chart;
                ITrace2D trace;

                if (data == null)
                {
                    // first instance of this graph, so create it

                    data = new SparklineData();
                    data.name = name;
                    data.isHovering = false;

                    chart = new Chart2D();

                    data.chart = chart;


                    cs.sparklines.put(name, data);

                    trace = new Trace2DLtd(chartData.sparklineChartSize, name);

                    chart.addTrace(trace);

                    // add marker lines to the trace
                    TracePainterDisc markerPainter = new TracePainterDisc();
                    markerPainter.setDiscSize(2);
                    trace.addTracePainter(markerPainter);

                } else {
                    chart = data.chart;
                    trace = chart.getTraces().first();
                }

                // update the positions every loop in case another section
                // was collapsed

                data.xmin = x[3];
                data.xmax = x[3]+sparklineWidth;
                data.ymin = y - textheight;
                data.ymax = y;

                // add the data to our trace
                if (updateGraphs)
                {
                    trace.addPoint((double)utime/1000000.0d, value);
                }

                // draw the graph
                DrawSparkline(x[3], y, trace, isHovering);




                //g.drawString(String.valueOf(thisValue), x[3], y);
            }
            /*
            } else {
                drawStrings(cls.getName(), name, o.toString(), isstatic);
                return;
            }
*/
            y+= textheight;

            g.setFont(of);
            g.setColor(oldColor);
        }

        /**
         * Draws a sparkline.
         *
         * @param x x-coordinate of the left side of the line
         * @param y y-coordinate of the top of the line
         * @param trace data for the sparkline
         * @param isHovering true if the mouse cursor is hovering over this row
         */
        public void DrawSparkline(int x, int y, ITrace2D trace, boolean isHovering)
        {

            if (trace.getSize() < 2)
            {
                return;
            }

            Graphics2D g2 = (Graphics2D) g;


            Iterator<ITracePoint2D> iter = trace.iterator();

            final int circleSize = 3;
            final int height = textheight;
            double numSecondsDisplayed = 5.0;
            final double width = sparklineWidth;

            //width = width * ((double)trace.getSize() / (double) trace.getMaxSize());

            if (trace.getMaxX() == trace.getMinX())
            {
                // no time series, don't draw anything
                return;
            }

            Color pointColor = Color.RED;
            Color lineColor = Color.BLACK;

            if (isHovering) {
                Color temp = pointColor;
                pointColor = lineColor;
                lineColor = temp;
            }

            double earliestTimeDisplayed = ((double)utime/(double)1000000.0 - numSecondsDisplayed);
         // decide on the main axis scale
            double xscale = (double)width / (double)(numSecondsDisplayed);

            if (trace.getMaxY() == trace.getMinY())
            {
                // divide by zero error coming up!
                // bail and draw a straight line down the center of the graph
                g2.setColor(lineColor);
                ITracePoint2D firstPoint = iter.next();

                int leftLineX = (int)((firstPoint.getX() - earliestTimeDisplayed) * xscale) + x;

                if (leftLineX < x)
                {
                    leftLineX = x;
                }

                g2.drawLine(leftLineX, y-(int)((double)height/(double)2), x+(int)width, y-(int)((double)height/(double)2));
                g2.setColor(pointColor);
                g2.fillOval(x + (int) width - 1, y-(int)((double)height/(double)2) - 1, circleSize, circleSize);
                return;
            }


            double yscale = height / (trace.getMaxY() - trace.getMinY());


            g2.setColor(lineColor);

            boolean first = true;

            double lastX = 0, lastY = 0, thisX, thisY;

            while (iter.hasNext())
            {
                ITracePoint2D point = iter.next();

                if (first)
                {
                    first = false;
                    lastX = (point.getX() - earliestTimeDisplayed) * xscale + x;
                    lastY = y - (point.getY() - trace.getMinY()) * yscale;
                } else {
                    thisX = (point.getX() - earliestTimeDisplayed) * xscale + x;
                    thisY = y - (point.getY() - trace.getMinY()) * yscale;

                    if (thisX >= x && lastX >= x)
                    {
                        g2.drawLine((int)lastX, (int)lastY, (int)thisX, (int)thisY);
                    }
                    lastX = thisX;
                    lastY = thisY;
                }

                if (!iter.hasNext())
                {
                    // this is the last point, bold it
                    g2.setColor(pointColor);
                    g2.fillOval((int)lastX - 1, (int)lastY - 1, 3, 3);
                    g2.setColor(lineColor);
                }
            }
        }


        public void spacer()
        {
            if (collapse_depth > 0)
                return;

            y+= textheight/2;
        }

        public void beginColorBlock()
        {
            if (collapse_depth > 0)
                return;

            color_level++;
            g.setColor(indentColors[color_level%indentColors.length]);
            g.fillRect(x[0] + indent_level*indentpx - indentpx/2, y - fm.getMaxAscent(), getWidth(), getHeight());
            g.setColor(Color.black);
        }

        public void endColorBlock()
        {
            if (collapse_depth > 0)
                return;

            color_level--;
            g.setColor(indentColors[color_level%indentColors.length]);
            g.fillRect(x[0] + indent_level*indentpx -indentpx/2, y - fm.getMaxAscent(), getWidth(), getHeight());
            g.setColor(Color.black);
        }

        public void indent()
        {
            indent_level++;
        }

        public void unindent()
        {
            indent_level--;
        }

        public void finish()
        {
            g.setColor(Color.white);
            g.fillRect(0, y, getWidth(), getHeight());
            updateGraphs = false;
        }
    }

    public void setObject(Object o, long utime)
    {
        this.o = o;
        this.utime = utime - chartData.getStartTime();
        this.updateGraphs = true;
        repaint();
    }

    public Dimension getPreferredSize()
    {
        return new Dimension(lastwidth, lastheight);
    }

    public Dimension getMinimumSize()
    {
        return getPreferredSize();
    }

    public Dimension getMaximumSize()
    {
        return getPreferredSize();
    }

    public void paint(Graphics g)
    {
        Graphics2D g2 = (Graphics2D) g;

        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);

        int width = getWidth(), height = getHeight();
        g.setColor(Color.white);
        g.fillRect(0, 0, width, height);

        g.setColor(Color.black);
        FontMetrics fm = g.getFontMetrics();

        PaintState ps = new PaintState();

        ps.panel = this;
        ps.g = g;
        ps.fm = fm;
        ps.textheight = 15;
        ps.y = ps.textheight;
        ps.indent_level=1;
        ps.x[0] = 0;
        ps.x[1] = Math.min(200, width/4);
        ps.x[2] = Math.min(ps.x[1]+200, 2*width/4);
        ps.x[3] = ps.x[2]+150;

        if (o != null)
            paintRecurse(g, ps, "", o.getClass(), o, false, -1);

        ps.finish();
        if (ps.y != lastheight) {
            lastheight = ps.y;
            invalidate();
            getParent().validate();
        }
    }

    void paintRecurse(Graphics g, PaintState ps, String name, Class cls, Object o, boolean isstatic, int section)
    {
        if (o == null) {
            ps.drawStrings(cls==null ? "(null)" : cls.getName(), name, "(null)", isstatic);
            return;
        }

        if (cls.isPrimitive() || cls.equals(Byte.TYPE)) {

            // This is our common case...
            Section cs = sections.get(section);
            SparklineData data = cs.sparklines.get(name); // if data == null, this graph doesn't exist yet

            if (data == null || visibleSparklines.contains(data))
            {
                ps.drawStringsAndGraph(cls, name, o, isstatic, section);
            } else {
                // this graph exists, but it is far away from the user's view
                // to save CPU power, we don't draw it
                ps.drawStrings(cls.getName(), name, o.toString(), isstatic);
            }
        } else if (o instanceof Enum) {

            ps.drawStrings(cls.getName(), name, ((Enum) o).name(), isstatic);

        } else if (cls.equals(String.class)) {

            ps.drawStrings("String", name, o.toString(), isstatic);

        } else if (cls.isArray())  {

            int sz = Array.getLength(o);
            int sec = ps.beginSection(cls.getComponentType()+"[]", name+"["+sz+"]", "");

            for (int i = 0; i < sz; i++)
                paintRecurse(g, ps, name+"["+i+"]", cls.getComponentType(), Array.get(o, i), isstatic, sec);

            ps.endSection(sec);

        } else {

            // it's a compound type. recurse.
            int sec = ps.beginSection(cls.getName(), name, "");

            // it's a class
            Field fs[] = cls.getFields();
            for (Field f : fs) {
                try {
                    paintRecurse(g, ps, f.getName(), f.getType(), f.get(o), isstatic || ((f.getModifiers()&Modifier.STATIC) != 0), sec);
                } catch (Exception ex) {
                    System.out.println(ex.getMessage());
                    ex.printStackTrace(System.out);
                }
            }

            ps.endSection(sec);
        }
    }

    public boolean isOptimizedDrawingEnabled()
    {
        return false;
    }

    class MyMouseAdapter extends MouseAdapter
    {
        public void mouseClicked(MouseEvent e)
        {
            int x = e.getX(), y = e.getY();

            // check to see if we have clicked on a row in the inspector
            // and should open a graph of the data
            if (doSparklineInteraction(e) == true)
            {
                return;
            }

            int bestsection = -1;

            // find the bottom-most section that contains the mouse click.
            for (int i = 0; i < sections.size(); i++)
            {
                Section cs = sections.get(i);

                if (x>=cs.x0 && x<=cs.x1 && y>=cs.y0 && y<=cs.y1) {
                    bestsection = i;
                }
            }

            if (bestsection >= 0)
                sections.get(bestsection).collapsed ^= true;

            // call repaint here so the UI will update immediately instead of
            // waiting for the next piece of data
            repaint();
        }
    }

    class MyMouseMotionListener extends MouseMotionAdapter
    {

        public void mouseMoved(MouseEvent e)
        {
            // check to see if we are hovering over any rows of data
            doSparklineInteraction(e);

            // repaint in case the hovering changed
            repaint();
        }
    }
    
    class MyViewportChangeListener implements ChangeListener
    {
        public void stateChanged(ChangeEvent e)
        {
            // here we build a list of the items that are visible
            // or are close to visible to the user.  That way, we can
            // only update sparkline charts that are close to what the
            // user is looking at, reducing CPU load with huge messages
            
            JViewport viewport = (JViewport) e.getSource();
            
            Rectangle view_rect = viewport.getViewRect();
            
            visibleSparklines.clear();
            
            for (int i = sections.size() -1; i > -1; i--)
            {
                Section section = sections.get(i);

                //if (section.y0 < y && section.y1 > y)
                {
                    // we might be hovering over something in this section

                    Iterator<Entry<String, SparklineData>> it = section.sparklines.entrySet().iterator();
                    while (it.hasNext())
                    {
                        Entry<String, SparklineData> pair = it.next();

                        SparklineData data = pair.getValue();
                        
                        if (data.ymin > view_rect.y - sparklineDrawMargin && data.ymax < view_rect.y + view_rect.height + sparklineDrawMargin)
                        {
                            visibleSparklines.add(data);
                            System.out.print(".");
                        }
                        
                    }
                }
            }
        }
    }
}
