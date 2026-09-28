/*
 * Copyright (c) 2010-2026. Institut Pasteur.
 *
 * This file is part of Icy.
 * Icy is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Icy is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Icy. If not, see <https://www.gnu.org/licenses/>.
 */

package plugins.adufour.viewers;

import fr.icy.common.geom.point.Point5D;
import fr.icy.extension.plugin.abstract_.Plugin;
import fr.icy.extension.plugin.annotation_.IcyPluginName;
import fr.icy.extension.plugin.interface_.PluginCanvas;
import fr.icy.gui.GraphicsUtil;
import fr.icy.gui.LookAndFeelUtil;
import fr.icy.gui.canvas.IcyCanvas2D;
import fr.icy.gui.canvas.Layer;
import fr.icy.gui.component.button.IcyButton;
import fr.icy.gui.component.icon.IcySVG;
import fr.icy.gui.viewer.Viewer;
import fr.icy.model.colormap.IcyColorMap;
import fr.icy.model.colormap.LinearColorMap;
import fr.icy.model.image.IcyBufferedImage;
import fr.icy.model.image.IcyBufferedImageUtil;
import fr.icy.model.lut.LUT;
import fr.icy.model.roi.ROI;
import fr.icy.model.sequence.DimensionId;
import fr.icy.model.sequence.SequenceEvent;
import fr.icy.system.preferences.CanvasPreferences;
import fr.icy.system.thread.SingleProcessor;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import plugins.adufour.vars.gui.model.IntegerRangeModel;
import plugins.adufour.vars.gui.swing.SwingVarEditor;
import plugins.adufour.vars.lang.Var;
import plugins.adufour.vars.lang.VarBoolean;
import plugins.adufour.vars.lang.VarDouble;
import plugins.adufour.vars.lang.VarInteger;
import plugins.adufour.vars.util.VarListener;
import plugins.adufour.viewers.ChannelMontage.ScrollableFlowLayout.Orientation;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

@IcyPluginName("Channel Montage")
public class ChannelMontage extends Plugin implements PluginCanvas<ChannelMontage.ChannelMontageCanvas> {
    private static final Logger LOGGER = Logger.getLogger(ChannelMontage.class.getName());

    @Override
    public String getCanvasClassName() {
        return ChannelMontage.class.getName();
    }

    @Override
    public ChannelMontageCanvas createCanvas(final Viewer viewer) {
        return new ChannelMontageCanvas(viewer);
    }

    public static class ChannelMontageCanvas extends IcyCanvas2D implements MouseWheelListener {
        final ScrollableFlowLayout layout = new ScrollableFlowLayout(Orientation.VERTICAL, FlowLayout.CENTER, 5, 5);

        final JPanel mainPanel = new JPanel(layout);

        final JScrollPane scrollPane;

        final ArrayList<ChannelView> channelViews;

        final VarBoolean grayscale = new VarBoolean("grayscale", false);

        final IcyColorMap grayMap = new LinearColorMap("gray", Color.white);

        final VarInteger maxSize = new VarInteger("Maximum image size (pixels)", 250);

        final VarInteger viewWidth = new VarInteger("width", 250);

        final VarInteger viewHeight = new VarInteger("height", 250);

        final VarDouble viewScale = new VarDouble("scale", 1.0);

        final VarInteger imageSize = new VarInteger("Plane size", 250);

        final VarInteger margin = new VarInteger("Margin", 5);

        // final IcySlider sizeSlider = new IcySlider(IcySlider.HORIZONTAL, 20, 800, 250);

        final JCheckBox lockHorizontal = new JCheckBox("Horiz. lock");

        final Point5D.Double mousePosition = new Point5D.Double();

        public ChannelMontageCanvas(final Viewer viewer) {
            super(viewer);

            setPositionC(-1);

            final int sizeC = getSequence().getSizeC();
            channelViews = new ArrayList<>(sizeC + 1);

            initialiseViews(sizeC);

            mainPanel.setOpaque(false);
            scrollPane = new JScrollPane(mainPanel, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
            add(scrollPane, BorderLayout.CENTER);

            grayscale.addListener(new VarListener<>() {

                @Override
                public void valueChanged(final Var<Boolean> source, final Boolean oldValue, final Boolean newValue) {
                    for (final ChannelView view : channelViews)
                        view.imageCache.updateLUT();
                }

                @Override
                public void referenceChanged(final Var<Boolean> source, final Var<? extends Boolean> oldReference, final Var<? extends Boolean> newReference) {
                }
            });

            updateTNav();
            updateZNav();

            getMouseImageInfosPanel().setInfoColorVisible(false);
            getMouseImageInfosPanel().setInfoCVisible(false);
            getMouseImageInfosPanel().setInfoXVisible(true);
            getMouseImageInfosPanel().setInfoYVisible(true);
            getMouseImageInfosPanel().setInfoDataVisible(true);

            mainPanel.addMouseWheelListener(this);
        }

        private void initialiseViews(final int sizeC) {
            for (final ChannelView cv : channelViews) {
                cv.removeMouseListener(cv);
                cv.removeMouseMotionListener(cv);
                cv.removeKeyListener(cv);
                cv.removeMouseWheelListener(cv);
            }

            channelViews.clear();

            // create the individual views
            for (int c = 0; c <= sizeC; c++) {
                final ChannelView channelView = new ChannelView(c < sizeC ? c : -1);
                channelView.setFocusable(true);
                channelViews.add(channelView);
            }

            resizeViews();
        }

        @Override
        public void customizeToolbar(final @NotNull JToolBar toolbar) {
            final Icon rgbIcon = IcySVG.RGB_IMAGE.getIcon(LookAndFeelUtil.getDefaultIconSize());
            final Icon grayIcon = IcySVG.GRAYSCALE_IMAGE.getIcon(LookAndFeelUtil.getDefaultIconSize());

            toolbar.addSeparator();

            final IcyButton color = new IcyButton(IcySVG.GRAYSCALE_IMAGE);
            color.setToolTipText("Switch to grayscale mode");
            color.setFocusable(false);
            color.addActionListener(e -> {
                if (grayscale.toggleValue()) {
                    color.setIcon(rgbIcon);
                    color.setToolTipText("Switch to color mode");
                }
                else {
                    color.setIcon(grayIcon);
                    color.setToolTipText("Switch to grayscale mode");
                }
            });
            toolbar.add(color);

            toolbar.add(new JLabel(" Image size: "));

            imageSize.setDefaultEditorModel(new IntegerRangeModel(250, 20, 2048, 1));
            imageSize.addListener(new VarListener<>() {
                @Override
                public void valueChanged(final Var<Integer> source, final Integer oldValue, final Integer newValue) {
                    resizeViews();
                }

                @Override
                public void referenceChanged(final Var<Integer> source, final Var<? extends Integer> oldReference, final Var<? extends Integer> newReference) {
                }
            });
            final SwingVarEditor<Integer> sizeEditor = (SwingVarEditor<Integer>) imageSize.createVarEditor();
            sizeEditor.setEnabled(true);
            toolbar.add(sizeEditor.getEditorComponent());

            toolbar.add(new JLabel(" Margin: "));

            margin.setDefaultEditorModel(new IntegerRangeModel(5, 0, 100, 1));
            margin.addListener(new VarListener<>() {
                @Override
                public void valueChanged(final Var<Integer> source, final Integer oldValue, final Integer newValue) {
                    layout.setHgap(newValue);
                    layout.setVgap(newValue);
                    mainPanel.revalidate();
                }

                @Override
                public void referenceChanged(final Var<Integer> source, final Var<? extends Integer> oldReference, final Var<? extends Integer> newReference) {
                }
            });
            final SwingVarEditor<Integer> marginEditor = (SwingVarEditor<Integer>) margin.createVarEditor();
            marginEditor.setEnabled(true);
            toolbar.add(marginEditor.getEditorComponent());

            lockHorizontal.addChangeListener(e -> {
                if (lockHorizontal.isSelected()) {
                    if (layout.orientation == Orientation.HORIZONTAL) return;
                    // make it horizontal
                    layout.setOrientation(Orientation.HORIZONTAL);
                }
                else {
                    if (layout.orientation == Orientation.VERTICAL) return;
                    // make it vertical
                    layout.setOrientation(Orientation.VERTICAL);
                }

                resizeViews();
            });
            toolbar.add(lockHorizontal);
        }

        @Override
        public double getMouseImagePosX() {
            return mousePosition.getX();
        }

        @Override
        public double getMouseImagePosY() {
            return mousePosition.getY();
        }

        public void resizeViews() {
            // remove existing views
            mainPanel.removeAll();

            // compute view size and ratio

            if (getSequence().getWidth() > getSequence().getHeight()) {
                // viewWidth.setValue(sizeSlider.getValue());
                viewWidth.setValue(imageSize.getValue());
                viewScale.setValue(viewWidth.getValue() / (double) getSequence().getWidth());
                viewHeight.setValue((int) (viewScale.getValue() * getSequence().getHeight()));
            }
            else {
                // viewHeight.setValue(sizeSlider.getValue());
                viewHeight.setValue(imageSize.getValue());
                viewScale.setValue(viewHeight.getValue() / (double) getSequence().getHeight());
                viewWidth.setValue((int) (viewScale.getValue() * getSequence().getWidth()));
            }

            // give the scale to the canvas (needed for drawing and mouse input)
            // setScale(viewScale.getValue(), viewScale.getValue(), false);

            scaleChanged(DimensionId.X);
            // scaleChanged(DimensionId.Y);

            for (final ChannelView view : channelViews) {
                view.setPreferredSize(new Dimension(viewWidth.getValue(), viewHeight.getValue()));
                mainPanel.add(view);
            }

            mainPanel.revalidate();
        }

        @Override
        public double getScaleX() {
            return viewScale.getValue();
        }

        @Override
        public double getScaleY() {
            return viewScale.getValue();
        }

        @Override
        public void layerChanged(final Layer layer, final String propertyName) {
            super.layerChanged(layer, propertyName);
            repaint();
        }

        @Override
        protected void lutChanged(final int channel) {
            super.lutChanged(channel);

            if (channelViews == null) return;

            if (channel == -1) {
                initialiseViews(getSequence().getSizeC());
                refresh();
                return;
            }

            final ChannelView currentView = channelViews.get(channel);
            final ChannelView globalView = channelViews.getLast();

            currentView.imageCache.updateLUT();
            currentView.imageChanged();
            currentView.repaint();

            globalView.imageCache.updateLUT();
            globalView.imageChanged();
            globalView.repaint();

            refresh();
        }

        @Override
        protected void positionChanged(final DimensionId dim) {
            super.positionChanged(dim);

            if (channelViews != null && (dim == DimensionId.T || dim == DimensionId.Z)) {
                for (final ChannelView view : channelViews) {
                    view.imageChanged();
                }

                repaint();
            }
        }

        @Override
        protected void sequenceROIChanged(final ROI roi, final SequenceEvent.SequenceEventType type) {
            super.sequenceROIChanged(roi, type);
            repaint();
        }

        @Override
        public void repaint() {
            if (channelViews == null) return;

            for (final ChannelView view : channelViews)
                if (view != null) view.repaint();
        }

        @Override
        public void keyPressed(final KeyEvent e) {
            for (final ChannelView view : channelViews)
                view.keyPressed(e);
        }

        @Override
        public void keyReleased(final KeyEvent e) {
            for (final ChannelView view : channelViews)
                view.keyReleased(e);
        }

        public class ChannelView extends JPanel implements MouseWheelListener, MouseListener, MouseMotionListener, KeyListener {
            private final Timer refreshTimer = new Timer(0, null);

            public class ImageCache implements Runnable {
                final LUT lut;

                private BufferedImage imageCache;

                private final SingleProcessor processor;

                private boolean needRebuild;

                final IcyColorMap defaultColorMap = (channel == -1) ? null : getLut().getLutChannel(channel).getColorMap();

                public ImageCache() {
                    super();

                    lut = getCurrentImage().createCompatibleLUT();
                    updateLUT();

                    grayscale.addListener(new VarListener<>() {
                        @Override
                        public void valueChanged(final Var<Boolean> source, final Boolean oldValue, final Boolean newValue) {
                            updateLUT();
                        }

                        @Override
                        public void referenceChanged(final Var<Boolean> source, final Var<? extends Boolean> oldReference, final Var<? extends Boolean> newReference) {

                        }
                    });

                    processor = new SingleProcessor(true, "SplitChannel renderer (channel " + channel + ")");
                    // we want the processor to stay alive for sometime
                    processor.setKeepAliveTime(3, TimeUnit.SECONDS);

                    imageCache = null;
                    needRebuild = true;
                    // build cache
                    processor.submit(this);
                }

                @SuppressWarnings("UnstableApiUsage")
                @Contract(mutates = "this")
                private void invalidCache() {
                    needRebuild = true;
                }

                public void updateLUT() {
                    if (getCurrentImage() == null) return;

                    lut.beginUpdate();

                    lut.setColorMaps(getLut(), true);
                    lut.setScalers(getLut());

                    if (channel != -1) {
                        for (int c = 0; c < getCurrentImage().getSizeC(); c++) {
                            final LUT.LUTChannel lutC = lut.getLutChannel(c);
                            lutC.setEnabled(c == channel);
                            if (grayscale.getValue()) lutC.setColorMap(grayMap, false);
                        }

                        if (imageCache != null) imageChanged();
                    }

                    lut.endUpdate();

                    repaint();
                }

                public boolean isValid() {
                    return !needRebuild;
                }

                public boolean isProcessing() {
                    return processor.isProcessing();
                }

                public void refresh(final boolean wait) {
                    if (needRebuild) {
                        final Future<?> result = processor.submit(this);
                        if (wait) try {
                            result.get();
                        }
                        catch (final Exception e) {
                            if (LOGGER.isLoggable(Level.SEVERE))
                                LOGGER.log(Level.SEVERE, "Unable to refresh.", e);
                        }
                    }

                    // just repaint
                    repaint();
                }

                public BufferedImage getImage() {
                    return imageCache;
                }

                @Override
                public void run() {
                    // important to set it to false at beginning
                    needRebuild = false;

                    final IcyBufferedImage img = getCurrentImage();

                    if (img != null) {
                        lut.setScalers(getLut());
                        try {
                            imageCache = IcyBufferedImageUtil.toBufferedImage(img, imageCache, lut);
                        }
                        catch (final InterruptedException e) {
                            // ignore
                        }
                    }
                    else imageCache = null;

                    // repaint now
                    repaint();
                }
            }

            /**
             * Image cache
             */
            final ImageCache imageCache;

            /**
             * internals
             */
            private final Font font;

            public final int channel;

            public ChannelView(final int channel) {
                super();

                this.channel = channel;

                imageCache = new ImageCache();

                font = new Font("Arial", Font.BOLD, 16);

                addMouseListener(this);
                addMouseMotionListener(this);
                addMouseWheelListener(this);
            }

            @Override
            public void mouseClicked(final MouseEvent e) {
                // send mouse event to painters first
                for (final Layer layer : getVisibleLayers())
                    layer.getOverlay().mouseClick(e, mousePosition, ChannelMontageCanvas.this);
            }

            @Override
            public void mousePressed(final MouseEvent e) {
                // send mouse event to painters first
                for (final Layer layer : getVisibleLayers())
                    layer.getOverlay().mousePressed(e, mousePosition, ChannelMontageCanvas.this);
            }

            @Override
            public void mouseReleased(final MouseEvent e) {
                // send mouse event to painters after
                for (final Layer layer : getVisibleLayers())
                    layer.getOverlay().mouseReleased(e, mousePosition, ChannelMontageCanvas.this);
            }

            @Override
            public void mouseEntered(final MouseEvent e) {
            }

            @Override
            public void mouseExited(final MouseEvent e) {
            }

            @Override
            public void mouseMoved(final @NotNull MouseEvent e) {
                final Point2D p2 = canvasToImage(e.getPoint());
                mousePosition.setX(p2.getX());
                mousePosition.setY(p2.getY());

                mouseImagePositionChanged(DimensionId.X);

                for (final Layer layer : getLayers(true))
                    layer.getOverlay().mouseMove(e, mousePosition, ChannelMontageCanvas.this);

                repaint();
            }

            @Override
            public void mouseDragged(final @NotNull MouseEvent e) {
                final Point2D p2 = canvasToImage(e.getPoint());
                mousePosition.setX(p2.getX());
                mousePosition.setY(p2.getY());

                for (final Layer layer : getLayers(true))
                    layer.getOverlay().mouseDrag(e, mousePosition, ChannelMontageCanvas.this);

                repaint();
            }

            @Override
            public void mouseWheelMoved(final @NotNull MouseWheelEvent e) {
                // sizeSlider.setValue(sizeSlider.getValue() + e.getWheelRotation());
                imageSize.setValue(imageSize.getValue() + e.getWheelRotation());
            }

            @Override
            public void keyTyped(final KeyEvent e) {
            }

            @Override
            public void keyPressed(final KeyEvent e) {
                // send event to painters after
                for (final Layer layer : getLayers(true))
                    layer.getOverlay().keyPressed(e, mousePosition, ChannelMontageCanvas.this);
            }

            @Override
            public void keyReleased(final KeyEvent e) {
                // send event to painters after
                for (final Layer layer : getLayers(true))
                    layer.getOverlay().keyPressed(e, mousePosition, ChannelMontageCanvas.this);
            }

            @Override
            protected void paintComponent(final Graphics g) {
                // pre-paint
                super.paintComponent(g);

                // check if the image data exists

                final BufferedImage img = imageCache.getImage();

                if (img != null) {
                    // paint the image data

                    final Graphics2D g2 = (Graphics2D) g.create();

                    g2.transform(getTransform());

                    if (CanvasPreferences.getFiltering()) {
                        if (getScaleX() < 4d && getScaleY() < 4d) {
                            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                        }
                        else {
                            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                        }
                    }

                    // paint the layers

                    if (!isLayersVisible()) {
                        g2.drawImage(img, null, null);
                    }
                    else {
                        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                        final List<Layer> currentLayers = getLayers(true);

                        // draw them in inverse order to have first painter event at top
                        // every layer but the first (i.e. no image, we draw it ourselves)
                        for (int i = currentLayers.size() - 1; i >= 0; i--) {
                            final Layer layer = currentLayers.get(i);

                            if (!layer.isVisible()) continue;

                            Composite composite = AlphaComposite.SrcOver;

                            final float alpha = layer.getOpacity();
                            if (alpha != 1f) composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha);

                            g2.setComposite(composite);

                            if (layer == getImageLayer()) {
                                g2.drawImage(img, null, null);
                            }
                            else {
                                layer.getOverlay().paint(g2, getSequence(), ChannelMontageCanvas.this);
                            }
                        }
                    }
                    g2.dispose();
                }
                else {
                    final Graphics2D g2 = (Graphics2D) g.create();

                    g2.setFont(font);
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                    if (ChannelMontageCanvas.this.getCurrentImage() != null)
                        // cache not yet built
                        drawTextCenter(g2, "Loading...", 0.8f);
                    else
                        // no image
                        drawTextCenter(g2, " No image ", 0.8f);

                    g2.dispose();
                }

                // image or layers changed during repaint --> refresh again

                if (!isCacheValid()) refresh(false);
            }

            public void drawTextBottomRight(final Graphics2D g, final String text, final float alpha) {
                final Rectangle2D rect = GraphicsUtil.getStringBounds(g, text);
                final int w = (int) rect.getWidth();
                final int h = (int) rect.getHeight();
                final int x = getWidth() - (w + 8 + 2);
                final int y = getHeight() - (h + 8 + 2);

                g.setColor(Color.gray);
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
                g.fillRoundRect(x, y, w + 8, h + 8, 8, 8);

                g.setColor(Color.white);
                g.drawString(text, x + 4, y + 2 + h);
            }

            public void drawTextTopRight(final Graphics2D g, final String text, final float alpha) {
                final Rectangle2D rect = GraphicsUtil.getStringBounds(g, text);
                final int w = (int) rect.getWidth();
                final int h = (int) rect.getHeight();
                final int x = getWidth() - (w + 8 + 2);
                final int y = 2;

                g.setColor(Color.gray);
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
                g.fillRoundRect(x, y, w + 8, h + 8, 8, 8);

                g.setColor(Color.white);
                g.drawString(text, x + 4, y + 2 + h);
            }

            public void drawTextCenter(final Graphics2D g, final String text, final float alpha) {
                final Rectangle2D rect = GraphicsUtil.getStringBounds(g, text);
                final int w = (int) rect.getWidth();
                final int h = (int) rect.getHeight();
                final int x = (getWidth() - (w + 8 + 2)) / 2;
                final int y = (getHeight() - (h + 8 + 2)) / 2;

                g.setColor(Color.gray);
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
                g.fillRoundRect(x, y, w + 8, h + 8, 8, 8);

                g.setColor(Color.white);
                g.drawString(text, x + 4, y + 2 + h);
            }

            @Override
            public void repaint() {
                super.repaint();
            }

            public void refresh(final boolean wait) {
                imageCache.refresh(wait);
            }

            /**
             * Refresh in sometime
             *
             * @param milli int time in milliseconds
             */
            public void refreshLater(final int milli) {
                refreshTimer.setInitialDelay(milli);
                refreshTimer.start();
            }

            public void imageChanged() {
                imageCache.invalidCache();
            }

            public void layersChanged() {
                repaint();
            }

            public boolean isCacheValid() {
                return imageCache.isValid();
            }
        }

        @Override
        public Component getViewComponent() {
            return this;
        }

        @Override
        public void refresh() {
            refresh(false);
        }

        public void refresh(final boolean wait) {
            for (final ChannelView view : new ArrayList<>(channelViews))
                view.refresh(wait);
        }

        @Override
        public BufferedImage getRenderedImage(final int t, final int z, final int c, final boolean canvasView) {
            setPositionT(t);
            setPositionZ(z);
            refresh(true);

            // create the raw screenshot
            final Dimension size = mainPanel.getSize();
            BufferedImage screenshot = new BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB);
            final Graphics2D g2 = screenshot.createGraphics();

//            g2.setBackground(mainPanel.getBackground());
//            g2.clearRect(0, 0, size.width, size.height);

            final boolean doubleBuffered = mainPanel.isDoubleBuffered();
            mainPanel.setDoubleBuffered(false);
            mainPanel.paintAll(g2);
            mainPanel.setDoubleBuffered(doubleBuffered);

            g2.dispose();

            // cut the extra horizontal spacers (if any)
            final Dimension tightSize = mainPanel.getPreferredSize();
            final int x = (size.width - tightSize.width) / 2;
            screenshot = screenshot.getSubimage(x, 0, tightSize.width, tightSize.height);

            return screenshot;
        }

        @Override
        public void mouseWheelMoved(final @NotNull MouseWheelEvent e) {
            // sizeSlider.setValue(sizeSlider.getValue() + e.getWheelRotation());
            imageSize.setValue(imageSize.getValue() + e.getWheelRotation());
        }
    }

    /**
     * A modified version of FlowLayout that allows containers using this Layout to behave in a
     * reasonable manner when placed inside a JScrollPane. <br>
     * This layout has a static orientation, such that components will remain in a single row or
     * column, allowing them to be scrollable
     *
     * @author Babu Kalakrishnan Modifications by greearb and jzd
     * @author Modified 2014/02/27 by Alexandre Dufour (adufour@pasteur.fr) to handle horizontal and
     * vertical alignments with a single class
     */
    static class ScrollableFlowLayout extends FlowLayout {
        public enum Orientation {
            HORIZONTAL, VERTICAL
        }

        private Orientation orientation;

        public ScrollableFlowLayout(final Orientation orientation, final int align, final int hgap, final int vgap) {
            super(align, hgap, vgap);
            this.orientation = orientation;
        }

        public Orientation getOrientation() {
            return orientation;
        }

        public void setOrientation(final Orientation orientation) {
            this.orientation = orientation;
        }

        @Override
        public Dimension minimumLayoutSize(final Container target) {
            // Size of largest component, so we can resize it in
            // either direction with something like a split-pane.
            return computeMinSize(target);
        }

        @Override
        public Dimension preferredLayoutSize(final Container target) {
            if (orientation == Orientation.VERTICAL) return computeSize(target);

            return super.preferredLayoutSize(target);
        }

        @Contract("_ -> new")
        private static @NotNull Dimension computeMinSize(final @NotNull Container target) {
            synchronized (target.getTreeLock()) {
                int minx = Integer.MAX_VALUE;
                //int miny = Integer.MIN_VALUE;
                int miny = Integer.MAX_VALUE;
                boolean found_one = false;
                final int n = target.getComponentCount();

                for (int i = 0; i < n; i++) {
                    final Component c = target.getComponent(i);
                    if (c.isVisible()) {
                        found_one = true;
                        final Dimension d = c.getPreferredSize();
                        minx = Math.min(minx, d.width);
                        miny = Math.min(miny, d.height);
                    }
                }
                if (found_one) {
                    return new Dimension(minx, miny);
                }
                return new Dimension(0, 0);
            }
        }

        @Contract("_ -> new")
        private @NotNull Dimension computeSize(final @NotNull Container target) {
            synchronized (target.getTreeLock()) {
                final int hgap = getHgap();
                final int vgap = getVgap();
                int w = target.getWidth();

                // Let this behave like a regular FlowLayout (single row)
                // if the container hasn't been assigned any size yet
                if (w == 0) {
                    w = Integer.MAX_VALUE;
                }

                Insets insets = target.getInsets();
                if (insets == null) {
                    insets = new Insets(0, 0, 0, 0);
                }
                int reqdWidth = 0;

                final int maxwidth = w - (insets.left + insets.right + hgap * 2);
                final int n = target.getComponentCount();
                int x = 0;
                int y = insets.top + vgap; // FlowLayout starts by adding vgap, so do that here too.
                int rowHeight = 0;

                for (int i = 0; i < n; i++) {
                    final Component c = target.getComponent(i);
                    if (c.isVisible()) {
                        final Dimension d = c.getPreferredSize();
                        if ((x == 0) || ((x + d.width) <= maxwidth)) {
                            // fits in current row.
                            if (x > 0) {
                                x += hgap;
                            }
                            x += d.width;
                            rowHeight = Math.max(rowHeight, d.height);
                        }
                        else {
                            // Start of new row
                            x = d.width;
                            y += vgap + rowHeight;
                            rowHeight = d.height;
                        }
                        reqdWidth = Math.max(reqdWidth, x);
                    }
                }
                y += rowHeight;
                y += insets.bottom;
                return new Dimension(reqdWidth + insets.left + insets.right, y);
            }
        }
    }
}
