package plugins.adufour.viewers;

import java.awt.AlphaComposite;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Composite;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JToolBar;
import javax.swing.Timer;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;

import icy.canvas.IcyCanvas;
import icy.canvas.IcyCanvas2D;
import icy.canvas.Layer;
import icy.gui.component.button.IcyButton;
import icy.gui.viewer.Viewer;
import icy.image.IcyBufferedImage;
import icy.image.IcyBufferedImageUtil;
import icy.image.colormap.IcyColorMap;
import icy.image.colormap.LinearColorMap;
import icy.image.lut.LUT;
import icy.image.lut.LUT.LUTChannel;
import icy.plugin.abstract_.Plugin;
import icy.plugin.interface_.PluginCanvas;
import icy.preferences.CanvasPreferences;
import icy.resource.ResourceUtil;
import icy.resource.icon.IcyIcon;
import icy.roi.ROI;
import icy.sequence.DimensionId;
import icy.sequence.SequenceEvent.SequenceEventType;
import icy.system.thread.SingleProcessor;
import icy.type.point.Point5D;
import icy.util.GraphicsUtil;
import plugins.adufour.vars.gui.model.IntegerRangeModel;
import plugins.adufour.vars.gui.swing.SwingVarEditor;
import plugins.adufour.vars.lang.Var;
import plugins.adufour.vars.lang.VarBoolean;
import plugins.adufour.vars.lang.VarDouble;
import plugins.adufour.vars.lang.VarInteger;
import plugins.adufour.vars.util.VarListener;
import plugins.adufour.viewers.ChannelMontage.ScrollableFlowLayout.Orientation;

public class ChannelMontage extends Plugin implements PluginCanvas
{
    @Override
    public String getCanvasClassName()
    {
        return ChannelMontage.class.getName();
    }
    
    @Override
    public IcyCanvas createCanvas(Viewer viewer)
    {
        return new ChannelMontageCanvas(viewer);
    }
    
    @SuppressWarnings("serial")
    public class ChannelMontageCanvas extends IcyCanvas2D implements MouseWheelListener
    {
        final ScrollableFlowLayout layout = new ScrollableFlowLayout(ScrollableFlowLayout.Orientation.VERTICAL, FlowLayout.CENTER, 5, 5);
        
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
        
        public ChannelMontageCanvas(Viewer viewer)
        {
            super(viewer);
            
            setPositionC(-1);
            
            int sizeC = getSequence().getSizeC();
            channelViews = new ArrayList<ChannelView>(sizeC + 1);
            
            initialiseViews(sizeC);
            
            mainPanel.setOpaque(false);
            scrollPane = new JScrollPane(mainPanel, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
            add(scrollPane, BorderLayout.CENTER);
            
            grayscale.addListener(new VarListener<Boolean>()
            {
                
                @Override
                public void valueChanged(Var<Boolean> source, Boolean oldValue, Boolean newValue)
                {
                    for (ChannelView view : channelViews)
                        view.imageCache.updateLUT();
                }
                
                @Override
                public void referenceChanged(Var<Boolean> source, Var<? extends Boolean> oldReference, Var<? extends Boolean> newReference)
                {
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
        
        private void initialiseViews(int sizeC)
        {
            for (ChannelView cv : channelViews)
            {
                cv.removeMouseListener(cv);
                cv.removeMouseMotionListener(cv);
                cv.removeKeyListener(cv);
                cv.removeMouseWheelListener(cv);
            }
            
            channelViews.clear();
            
            // create the individual views
            for (int c = 0; c <= sizeC; c++)
            {
                ChannelView channelView = new ChannelView(c < sizeC ? c : -1);
                channelView.setFocusable(true);
                channelViews.add(channelView);
            }
            
            resizeViews();
        }
        
        @Override
        public void customizeToolbar(JToolBar toolbar)
        {
            final IcyIcon rgbIcon = new IcyIcon(ResourceUtil.ICON_RGB_COLOR, false);
            final IcyIcon grayIcon = new IcyIcon(ResourceUtil.ICON_GRAY_COLOR, false);
            
            toolbar.addSeparator();
            
            final IcyButton color = new IcyButton(grayIcon);
            color.setToolTipText("Switch to grayscale mode");
            color.setFocusable(false);
            color.addActionListener(new ActionListener()
            {
                @Override
                public void actionPerformed(ActionEvent e)
                {
                    if (grayscale.toggleValue())
                    {
                        color.setIcon(rgbIcon);
                        color.setToolTipText("Switch to color mode");
                    }
                    else
                    {
                        color.setIcon(grayIcon);
                        color.setToolTipText("Switch to grayscale mode");
                    }
                }
            });
            toolbar.add(color);
            
            toolbar.add(new JLabel(" Image size: "));
            
            imageSize.setDefaultEditorModel(new IntegerRangeModel(250, 20, 2048, 1));
            imageSize.addListener(new VarListener<Integer>()
            {
                
                @Override
                public void valueChanged(Var<Integer> source, Integer oldValue, Integer newValue)
                {
                    resizeViews();
                }
                
                @Override
                public void referenceChanged(Var<Integer> source, Var<? extends Integer> oldReference, Var<? extends Integer> newReference)
                {
                }
            });
            SwingVarEditor<Integer> sizeEditor = (SwingVarEditor<Integer>) imageSize.createVarEditor();
            sizeEditor.setEnabled(true);
            toolbar.add(sizeEditor.getEditorComponent());
            
            toolbar.add(new JLabel(" Margin: "));
            
            margin.setDefaultEditorModel(new IntegerRangeModel(5, 0, 100, 1));
            margin.addListener(new VarListener<Integer>()
            {
                
                @Override
                public void valueChanged(Var<Integer> source, Integer oldValue, Integer newValue)
                {
                    layout.setHgap(newValue);
                    layout.setVgap(newValue);
                    mainPanel.revalidate();
                }
                
                @Override
                public void referenceChanged(Var<Integer> source, Var<? extends Integer> oldReference, Var<? extends Integer> newReference)
                {
                }
            });
            SwingVarEditor<Integer> marginEditor = (SwingVarEditor<Integer>) margin.createVarEditor();
            marginEditor.setEnabled(true);
            toolbar.add(marginEditor.getEditorComponent());
            
            lockHorizontal.addChangeListener(new ChangeListener()
            {
                @Override
                public void stateChanged(ChangeEvent e)
                {
                    if (lockHorizontal.isSelected())
                    {
                        if (layout.orientation == Orientation.HORIZONTAL) return;
                        // make it horizontal
                        layout.setOrientation(Orientation.HORIZONTAL);
                    }
                    else
                    {
                        if (layout.orientation == Orientation.VERTICAL) return;
                        // make it vertical
                        layout.setOrientation(Orientation.VERTICAL);
                    }
                    
                    resizeViews();
                }
            });
            toolbar.add(lockHorizontal);
        }
        
        @Override
        public double getMouseImagePosX()
        {
            return mousePosition.getX();
        }
        
        @Override
        public double getMouseImagePosY()
        {
            return mousePosition.getY();
        }
        
        public void resizeViews()
        {
            // remove existing views
            mainPanel.removeAll();
            
            // compute view size and ratio
            
            if (getSequence().getWidth() > getSequence().getHeight())
            {
                // viewWidth.setValue(sizeSlider.getValue());
                viewWidth.setValue(imageSize.getValue());
                viewScale.setValue(viewWidth.getValue() / (double) getSequence().getWidth());
                viewHeight.setValue((int) (viewScale.getValue() * getSequence().getHeight()));
            }
            else
            {
                // viewHeight.setValue(sizeSlider.getValue());
                viewHeight.setValue(imageSize.getValue());
                viewScale.setValue(viewHeight.getValue() / (double) getSequence().getHeight());
                viewWidth.setValue((int) (viewScale.getValue() * getSequence().getWidth()));
            }
            
            // give the scale to the canvas (needed for drawing and mouse input)
            // setScale(viewScale.getValue(), viewScale.getValue(), false);
            
            scaleChanged(DimensionId.X);
            // scaleChanged(DimensionId.Y);
            
            for (ChannelView view : channelViews)
            {
                view.setPreferredSize(new Dimension(viewWidth.getValue(), viewHeight.getValue()));
                mainPanel.add(view);
            }
            
            mainPanel.revalidate();
        }
        
        @Override
        public double getScaleX()
        {
            return viewScale.getValue();
        }
        
        @Override
        public double getScaleY()
        {
            return viewScale.getValue();
        }
        
        @Override
        public void layerChanged(Layer layer, String propertyName)
        {
            super.layerChanged(layer, propertyName);
            repaint();
        }
        
        @Override
        protected void lutChanged(int channel)
        {
            super.lutChanged(channel);
            
            if (channelViews == null) return;
            
            if (channel == -1)
            {
                initialiseViews(getSequence().getSizeC());
                refresh();
                return;
            }
            
            ChannelView currentView = channelViews.get(channel);
            ChannelView globalView = channelViews.get(channelViews.size() - 1);
            
            currentView.imageCache.updateLUT();
            currentView.imageChanged();
            currentView.repaint();
            
            globalView.imageCache.updateLUT();
            globalView.imageChanged();
            globalView.repaint();
            
            refresh();
        }
        
        @Override
        protected void positionChanged(DimensionId dim)
        {
            super.positionChanged(dim);
            
            if (channelViews != null && (dim == DimensionId.T || dim == DimensionId.Z))
            {
                for (ChannelView view : channelViews)
                {
                    view.imageChanged();
                }
                
                repaint();
            }
        }
        
        @Override
        protected void sequenceROIChanged(ROI roi, SequenceEventType type)
        {
            super.sequenceROIChanged(roi, type);
            repaint();
        }
        
        @Override
        public void repaint()
        {
            if (channelViews == null) return;
            
            for (ChannelView view : channelViews)
                if (view != null) view.repaint();
        }
        
        @Override
        public void keyPressed(KeyEvent e)
        {
            for (ChannelView view : channelViews)
                view.keyPressed(e);
        }
        
        @Override
        public void keyReleased(KeyEvent e)
        {
            for (ChannelView view : channelViews)
                view.keyReleased(e);
        }
        
        public class ChannelView extends JPanel implements MouseWheelListener, MouseListener, MouseMotionListener, KeyListener
        {
            private Timer refreshTimer;
            
            public class ImageCache implements Runnable
            {
                final LUT lut;
                
                private BufferedImage imageCache;
                
                private final SingleProcessor processor;
                
                private boolean needRebuild;
                
                final IcyColorMap defaultColorMap = (channel == -1) ? null : getLut().getLutChannel(channel).getColorMap();
                
                public ImageCache()
                {
                    super();
                    
                    lut = getCurrentImage().createCompatibleLUT();
                    updateLUT();
                    
                    grayscale.addListener(new VarListener<Boolean>()
                    {
                        @Override
                        public void valueChanged(Var<Boolean> source, Boolean oldValue, Boolean newValue)
                        {
                            updateLUT();
                        }
                        
                        @Override
                        public void referenceChanged(Var<Boolean> source, Var<? extends Boolean> oldReference, Var<? extends Boolean> newReference)
                        {
                        
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
                
                private void invalidCache()
                {
                    needRebuild = true;
                }
                
                public void updateLUT()
                {
                    if (getCurrentImage() == null) return;
                    
                    lut.beginUpdate();
                    
                    lut.setColorMaps(getLut(), true);
                    lut.setScalers(getLut());
                    
                    if (channel != -1)
                    {
                        for (int c = 0; c < getCurrentImage().getSizeC(); c++)
                        {
                            final LUTChannel lutC = lut.getLutChannel(c);
                            lutC.setEnabled(c == channel);
                            if (grayscale.getValue()) lutC.setColorMap(grayMap, false);
                        }
                        
                        if (imageCache != null) imageChanged();
                    }
                    
                    lut.endUpdate();
                    
                    repaint();
                }
                
                public boolean isValid()
                {
                    return !needRebuild;
                }
                
                public boolean isProcessing()
                {
                    return processor.isProcessing();
                }
                
                public void refresh(boolean wait)
                {
                    if (needRebuild)
                    {
                        Future<?> result = processor.submit(this);
                        if (wait) try
                        {
                            result.get();
                        }
                        catch (Exception e)
                        {
                            e.printStackTrace();
                        }
                    }
                    
                    // just repaint
                    repaint();
                }
                
                public BufferedImage getImage()
                {
                    return imageCache;
                }
                
                @Override
                public void run()
                {
                    // important to set it to false at beginning
                    needRebuild = false;
                    
                    final IcyBufferedImage img = getCurrentImage();
                    
                    if (img != null)
                    {
                        lut.setScalers(getLut());
                        imageCache = IcyBufferedImageUtil.getARGBImage(img, lut, imageCache);
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
            
            public ChannelView(int channel)
            {
                super();
                
                this.channel = channel;
                
                imageCache = new ImageCache();
                
                font = new Font("Arial", Font.BOLD, 16);
                
                addMouseListener(this);
                addMouseMotionListener(this);
                addMouseWheelListener(this);
            }
            
            @Override
            public void mouseClicked(MouseEvent e)
            {
                // send mouse event to painters first
                for (Layer layer : getVisibleLayers())
                    layer.getOverlay().mouseClick(e, mousePosition, ChannelMontageCanvas.this);
            }
            
            @Override
            public void mousePressed(MouseEvent e)
            {
                // send mouse event to painters first
                for (Layer layer : getVisibleLayers())
                    layer.getOverlay().mousePressed(e, mousePosition, ChannelMontageCanvas.this);
            }
            
            @Override
            public void mouseReleased(MouseEvent e)
            {
                // send mouse event to painters after
                for (Layer layer : getVisibleLayers())
                    layer.getOverlay().mouseReleased(e, mousePosition, ChannelMontageCanvas.this);
            }
            
            @Override
            public void mouseEntered(MouseEvent e)
            {
            }
            
            @Override
            public void mouseExited(MouseEvent e)
            {
            }
            
            @Override
            public void mouseMoved(MouseEvent e)
            {
                Point2D p2 = canvasToImage(e.getPoint());
                mousePosition.setX(p2.getX());
                mousePosition.setY(p2.getY());
                
                mouseImagePositionChanged(DimensionId.X);
                
                for (Layer layer : getLayers(true))
                    layer.getOverlay().mouseMove(e, mousePosition, ChannelMontageCanvas.this);
                    
                repaint();
            }
            
            @Override
            public void mouseDragged(MouseEvent e)
            {
                Point2D p2 = canvasToImage(e.getPoint());
                mousePosition.setX(p2.getX());
                mousePosition.setY(p2.getY());
                
                for (Layer layer : getLayers(true))
                    layer.getOverlay().mouseDrag(e, mousePosition, ChannelMontageCanvas.this);
                    
                repaint();
            }
            
            @Override
            public void mouseWheelMoved(MouseWheelEvent e)
            {
                // sizeSlider.setValue(sizeSlider.getValue() + e.getWheelRotation());
                imageSize.setValue(imageSize.getValue() + e.getWheelRotation());
            }
            
            @Override
            public void keyTyped(KeyEvent e)
            {
            }
            
            @Override
            public void keyPressed(KeyEvent e)
            {
                // send event to painters after
                for (Layer layer : getLayers(true))
                    layer.getOverlay().keyPressed(e, mousePosition, ChannelMontageCanvas.this);
            }
            
            @Override
            public void keyReleased(KeyEvent e)
            {
                // send event to painters after
                for (Layer layer : getLayers(true))
                    layer.getOverlay().keyPressed(e, mousePosition, ChannelMontageCanvas.this);
            }
            
            @Override
            protected void paintComponent(Graphics g)
            {
                // pre-paint
                super.paintComponent(g);
                
                // check if the image data exists
                
                final BufferedImage img = imageCache.getImage();
                
                if (img != null)
                {
                    // paint the image data
                    
                    final Graphics2D g2 = (Graphics2D) g.create();
                    
                    g2.transform(getTransform());
                    
                    if (CanvasPreferences.getFiltering())
                    {
                        if (getScaleX() < 4d && getScaleY() < 4d)
                        {
                            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                        }
                        else
                        {
                            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                        }
                    }
                    
                    // paint the layers
                    
                    if (!isLayersVisible())
                    {
                        g2.drawImage(img, null, null);
                    }
                    else
                    {
                        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                        
                        final List<Layer> currentLayers = getLayers(true);
                        
                        // draw them in inverse order to have first painter event at top
                        // every layer but the first (i.e. no image, we draw it ourselves)
                        for (int i = currentLayers.size() - 1; i >= 0; i--)
                        {
                            final Layer layer = currentLayers.get(i);
                            
                            if (!layer.isVisible()) continue;
                            
                            Composite composite = AlphaComposite.SrcOver;
                            
                            final float alpha = layer.getOpacity();
                            if (alpha != 1f) composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha);
                            
                            g2.setComposite(composite);
                            
                            if (layer == getImageLayer())
                            {
                                g2.drawImage(img, null, null);
                            }
                            else
                            {
                                layer.getOverlay().paint(g2, getSequence(), ChannelMontageCanvas.this);
                            }
                        }
                    }
                    g2.dispose();
                }
                else
                {
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
            
            public void drawTextBottomRight(Graphics2D g, String text, float alpha)
            {
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
            
            public void drawTextTopRight(Graphics2D g, String text, float alpha)
            {
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
            
            public void drawTextCenter(Graphics2D g, String text, float alpha)
            {
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
            public void repaint()
            {
                super.repaint();
            }
            
            public void refresh(boolean wait)
            {
                imageCache.refresh(wait);
            }
            
            /**
             * Refresh in sometime
             * @param milli int time in milliseconds
             */
            public void refreshLater(int milli)
            {
                refreshTimer.setInitialDelay(milli);
                refreshTimer.start();
            }
            
            public void imageChanged()
            {
                imageCache.invalidCache();
            }
            
            public void layersChanged()
            {
                repaint();
            }
            
            public boolean isCacheValid()
            {
                return imageCache.isValid();
            }
            
        }
        
        @Override
        public Component getViewComponent()
        {
            return this;
        }
        
        public void refresh()
        {
            refresh(false);
        }
        
        public void refresh(boolean wait)
        {
            for (ChannelView view : new ArrayList<ChannelView>(channelViews))
                view.refresh(wait);
        }
        
        @Override
        public BufferedImage getRenderedImage(int t, int z, int c, boolean canvasView)
        {
            setPositionT(t);
            setPositionZ(z);
            refresh(true);
            
            // create the raw screenshot
            Dimension size = mainPanel.getSize();
            BufferedImage screenshot = new BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2 = screenshot.createGraphics();

//            g2.setBackground(mainPanel.getBackground());
//            g2.clearRect(0, 0, size.width, size.height);
            
            boolean doubleBuffered = mainPanel.isDoubleBuffered();
            mainPanel.setDoubleBuffered(false);
            mainPanel.paintAll(g2);
            mainPanel.setDoubleBuffered(doubleBuffered);
            
            g2.dispose();
            
            // cut the extra horizontal spacers (if any)
            Dimension tightSize = mainPanel.getPreferredSize();
            int x = (size.width - tightSize.width) / 2;
            screenshot = screenshot.getSubimage(x, 0, tightSize.width, tightSize.height);
            
            return screenshot;
        }
        
        @Override
        public void mouseWheelMoved(MouseWheelEvent e)
        {
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
     *         vertical alignments with a single class
     */
    @SuppressWarnings("serial")
    static class ScrollableFlowLayout extends FlowLayout
    {
        public enum Orientation
        {
            HORIZONTAL, VERTICAL
        }
        
        private Orientation orientation;
        
        public ScrollableFlowLayout(Orientation orientation, int align, int hgap, int vgap)
        {
            super(align, hgap, vgap);
            this.orientation = orientation;
        }
        
        public Orientation getOrientation()
        {
            return orientation;
        }
        
        public void setOrientation(Orientation orientation)
        {
            this.orientation = orientation;
        }
        
        public Dimension minimumLayoutSize(Container target)
        {
            // Size of largest component, so we can resize it in
            // either direction with something like a split-pane.
            return computeMinSize(target);
        }
        
        public Dimension preferredLayoutSize(Container target)
        {
            if (orientation == Orientation.VERTICAL) return computeSize(target);
            
            return super.preferredLayoutSize(target);
        }
        
        private static Dimension computeMinSize(Container target)
        {
            synchronized (target.getTreeLock())
            {
                int minx = Integer.MAX_VALUE;
                int miny = Integer.MIN_VALUE;
                boolean found_one = false;
                int n = target.getComponentCount();
                
                for (int i = 0; i < n; i++)
                {
                    Component c = target.getComponent(i);
                    if (c.isVisible())
                    {
                        found_one = true;
                        Dimension d = c.getPreferredSize();
                        minx = Math.min(minx, d.width);
                        miny = Math.min(miny, d.height);
                    }
                }
                if (found_one)
                {
                    return new Dimension(minx, miny);
                }
                return new Dimension(0, 0);
            }
        }
        
        private Dimension computeSize(Container target)
        {
            synchronized (target.getTreeLock())
            {
                int hgap = getHgap();
                int vgap = getVgap();
                int w = target.getWidth();
                
                // Let this behave like a regular FlowLayout (single row)
                // if the container hasn't been assigned any size yet
                if (w == 0)
                {
                    w = Integer.MAX_VALUE;
                }
                
                Insets insets = target.getInsets();
                if (insets == null)
                {
                    insets = new Insets(0, 0, 0, 0);
                }
                int reqdWidth = 0;
                
                int maxwidth = w - (insets.left + insets.right + hgap * 2);
                int n = target.getComponentCount();
                int x = 0;
                int y = insets.top + vgap; // FlowLayout starts by adding vgap, so do that here too.
                int rowHeight = 0;
                
                for (int i = 0; i < n; i++)
                {
                    Component c = target.getComponent(i);
                    if (c.isVisible())
                    {
                        Dimension d = c.getPreferredSize();
                        if ((x == 0) || ((x + d.width) <= maxwidth))
                        {
                            // fits in current row.
                            if (x > 0)
                            {
                                x += hgap;
                            }
                            x += d.width;
                            rowHeight = Math.max(rowHeight, d.height);
                        }
                        else
                        {
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
