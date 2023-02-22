/*
 *   Copyright 2008 Glencoe Software, Inc. All rights reserved.
 *   Use is subject to license terms supplied in LICENSE.txt
 */

package ome.services.fulltext;

import ome.io.nio.OriginalFilesService;
import ome.model.IAnnotated;
import ome.model.ILink;
import ome.model.IObject;
import ome.model.annotations.Annotation;
import ome.model.annotations.DoubleAnnotation;
import ome.model.annotations.FileAnnotation;
import ome.model.annotations.LongAnnotation;
import ome.model.annotations.MapAnnotation;
import ome.model.annotations.TagAnnotation;
import ome.model.annotations.TermAnnotation;
import ome.model.annotations.TextAnnotation;
import ome.model.containers.Folder;
import ome.model.core.Channel;
import ome.model.core.Image;
import ome.model.core.LogicalChannel;
import ome.model.core.OriginalFile;
import ome.model.core.Pixels;
import ome.model.fs.Fileset;
import ome.model.fs.FilesetEntry;
import ome.model.internal.Details;
import ome.model.internal.NamedValue;
import ome.model.internal.Permissions;
import ome.model.meta.Event;
import ome.model.meta.Experimenter;
import ome.model.meta.ExperimenterGroup;
import ome.model.roi.Roi;
import ome.util.DetailsFieldBridge;
import ome.util.Utils;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.Field.Index;
import org.apache.lucene.document.Field.Store;
import org.hibernate.proxy.HibernateProxy;
import org.hibernate.search.bridge.FieldBridge;
import org.hibernate.search.bridge.LuceneOptions;
import org.hibernate.search.bridge.builtin.DateBridge;

import java.io.Reader;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Primary definition of what will be indexed via Hibernate Search. This class
 * is delegated to by the {@link DetailsFieldBridge}, and further delegates to
 * classes as defined under "SearchBridges".
 *
 * @author Josh Moore, josh at glencoesoftware.com
 * @since 3.0-Beta3
 * @see <a href="https://omero.readthedocs.io/en/stable/developers/Search/FileParsers.html">Parsers</a>
 * @see <a href="https://omero.readthedocs.io/en/stable/developers/Modules/Search/Bridges.html">Bridges</a>
 */
@Deprecated
public class FullTextBridge extends BridgeHelper {
// TODO insert/update OR delete regular type OR annotated type OR originalfile

    final protected OriginalFilesService files;
    final protected Map<String, FileParser> parsers;
    final protected Class<FieldBridge>[] classes;
    final protected int maxFilesetSize;

    /**
     * Since this constructor provides the instance with no way of parsing
     * {@link OriginalFile} binaries, all files will be assumed to have blank
     * content. Further, no custom bridges are provided and so only the default
     * indexing will take place.
     */
    public FullTextBridge() {
        this(null, null);
    }

    /**
     * Constructor which provides an empty set of custom
     * {@link FieldBridge bridges}.
     */
    @SuppressWarnings("unchecked")
    public FullTextBridge(OriginalFilesService files,
            Map<String, FileParser> parsers) {
        this(files, parsers, new Class[] {});
    }

    /**
     * Main constructor.
     *
     * @param files
     *            {@link OriginalFilesService} for getting access to binary files.
     * @param parsers
     *            List of {@link FileParser} instances which are currently
     *            configured.
     * @param bridgeClasses
     *            set of {@link FieldBridge bridge classes} which will be
     *            instantiated via a no-arg constructor.
     * @see <a
     *      href="https://omero.readthedocs.io/en/stable/developers/Modules/Search/Bridges.html">Bridges</a>
     */
    @SuppressWarnings("unchecked")
    public FullTextBridge(OriginalFilesService files,
            Map<String, FileParser> parsers, Class<FieldBridge>[] bridgeClasses) {
        this(files, parsers, bridgeClasses, 1);
    }

    /**
     * Main constructor.
     *
     * @param files
     *            {@link OriginalFilesService} for getting access to binary files.
     * @param parsers
     *            List of {@link FileParser} instances which are currently
     *            configured.
     * @param bridgeClasses
     *            set of {@link FieldBridge bridge classes} which will be
     *            instantiated via a no-arg constructor.
     * @param maxFilesetSize
     *            maximum size of the fileset to be considered for indexing
     * @see <a
     *      href="https://omero.readthedocs.io/en/stable/developers/Modules/Search/Bridges.html">Bridges</a>
     */
    @SuppressWarnings("unchecked")
    public FullTextBridge(OriginalFilesService files,
            Map<String, FileParser> parsers, Class<FieldBridge>[] bridgeClasses,
            int maxFilesetSize) {
        this.files = files;
        this.parsers = parsers;
        this.classes = bridgeClasses == null ? new Class[] {} : bridgeClasses;
        this.maxFilesetSize = maxFilesetSize;
        logger().info("Maximum fileset size: {}", maxFilesetSize);
    }

    /**
     * Default implementation of the
     * {@link #set(String, Object, Document, LuceneOptions)}
     * method which calls
     * {@link #set_file(String, IObject, Document, LuceneOptions)}
     * {@link #set_annotations(String, IObject, Document, LuceneOptions)},
     * {@link #set_acquisition(String, IObject, Document, LuceneOptions)},
     * {@link #set_details(String, IObject, Document, LuceneOptions)},
     * {@link #set_fileset(String, IObject, Document, LuceneOptions)},
     * {@link #set_folders(String, IObject, Document, LuceneOptions)},
     * and finally
     * {@link #set_custom(String, IObject, Document, LuceneOptions)}.
     * as well as all {@link Annotation annotations}.
     */
    @Override
    public void set(String name, Object value, Document document, LuceneOptions opts) {

        IObject object = (IObject) value;

        // Store class in COMBINED
        String cls = Utils.trueClass(object.getClass()).getName();
        add(document, null, cls, opts);

        set_file(name, object, document, opts);
        set_annotations(name, object, document, opts);
        set_acquisition(name, object, document, opts);
        set_details(name, object, document, opts);
        set_fileset(name, object, document, opts);
        set_folders(name, object, document, opts);
        set_custom(name, object, document, opts);

    }

    /**
     * Uses {@link BridgeHelper#parse(OriginalFile, OriginalFilesService, Map)}
     * to get a {@link Reader} for the given
     * file which is then passed to
     * {@link #addContents(Document, String, OriginalFile, OriginalFilesService, Map, LuceneOptions)}
     * using the field name "file.contents".
     *
     * @param name
     * @param object
     * @param document
     * @param opts
     */
    public void set_file(final String name, final IObject object,
            final Document document, final LuceneOptions opts) {

        if (object instanceof OriginalFile) {
            OriginalFile file = (OriginalFile) object;
            addContents(document, "file.contents", file, files, parsers, opts);
        }

    }

    /**
     * Walks the various {@link Annotation} instances attached to the object
     * argument and adds various levels to the index.
     *
     * @param name
     * @param object
     * @param document
     * @param opts
     */
    public void set_annotations(final String name, final IObject object,
            final Document document, final LuceneOptions opts) {

        if (object instanceof ILink) {
            ILink link = (ILink) object;
            if (link.getChild() instanceof Annotation) {
                reindex(link.getParent());
            }
        }
        if (object instanceof IAnnotated) {
            IAnnotated annotated = (IAnnotated) object;
            List<Annotation> list = annotated.linkedAnnotationList();
            for (Annotation annotation : list) {
                if (annotation instanceof HibernateProxy) {
                    annotation = (Annotation) ((HibernateProxy) annotation).getHibernateLazyInitializer().getImplementation();
                }
                final String at = annotation.getClass().getSimpleName();
                add(document, "annotation.type", at, opts);
                if (annotation.getName() != null) {
                    add(document, "annotation.name", annotation.getName(), opts);
                }
                if (annotation.getNs() != null) {
                    add(document, "annotation.ns", annotation.getNs(), opts);
                }
                if (annotation instanceof TermAnnotation) {
                    TermAnnotation term = (TermAnnotation) annotation;
                    String termValue = term.getTermValue();
                    termValue = termValue == null ? "" : termValue;
                    add(document, "term", termValue, opts);
                } else if (annotation instanceof DoubleAnnotation) {
                    final Double value = ((DoubleAnnotation) annotation).getDoubleValue();
                    if (value != null) {
                        add(document, "annotation", value.toString(), opts);
                    }
                } else if (annotation instanceof LongAnnotation) {
                    final Long value = ((LongAnnotation) annotation).getLongValue();
                    if (value != null) {
                        add(document, "annotation", value.toString(), opts);
                    }
                } else if (annotation instanceof TextAnnotation) {
                    TextAnnotation text = (TextAnnotation) annotation;
                    String textValue = text.getTextValue();
                    textValue = textValue == null ? "" : textValue;
                    add(document, "annotation", textValue, opts);
                    if (annotation instanceof TagAnnotation) {
                        add(document, "tag", textValue, opts);
                        List<Annotation> list2 = annotation
                                .linkedAnnotationList();
                        for (Annotation annotation2 : list2) {
                            if (annotation2 instanceof TextAnnotation) {
                                TextAnnotation text2 = (TextAnnotation) annotation2;
                                String textValue2 = text2.getTextValue();
                                textValue2 = textValue2 == null ? ""
                                        : textValue2;
                                add(document, "annotation", textValue2, opts);
                            }
                        }
                    }
                } else if (annotation instanceof FileAnnotation) {
                    FileAnnotation fileAnnotation = (FileAnnotation) annotation;
                    handleFileAnnotation(document, opts, fileAnnotation);
                } else if (annotation instanceof MapAnnotation) {
                    MapAnnotation mapAnnotation = (MapAnnotation) annotation;
                    handleMapAnnotation(document, opts, mapAnnotation);
                }
            }
        }

        // Have to be careful here, since Annotations are also IAnnotated.
        // Don't use if/else
        if (object instanceof FileAnnotation) {
            FileAnnotation fileAnnotation = (FileAnnotation) object;
            handleFileAnnotation(document, opts, fileAnnotation);
        } else if (object instanceof MapAnnotation) {
            MapAnnotation mapAnnotation = (MapAnnotation) object;
            handleMapAnnotation(document, opts, mapAnnotation);
        }
    }

    /**
     * Walks the acquisition related metadata including channel names. This includes:
     *
     * - channel.name
     * - channel.fluor
     * - channel.mode
     * - channel.photometricInterpretation
     *
     * @param name
     * @param object
     * @param document
     * @param opts
     */
    public void set_acquisition(final String name, final IObject object,
                                final Document document, final LuceneOptions opts) {
        if (object instanceof Image) {
            final Image image = (Image) object;
            if (image.sizeOfPixels() == 0) {
                return;
            }
            final Pixels pixels = image.getPrimaryPixels();
            if (pixels == null) {
                return;
            }
            final Iterator<Channel> channelIterator = pixels.iterateChannels();
            while (channelIterator.hasNext()) {
                final Channel channel = channelIterator.next();
                if (channel == null) {
                    continue;
                }
                final LogicalChannel logical = channel.getLogicalChannel();
                if (logical == null) {
                    continue;
                }
                addIfNotNull(document, "channel.name", logical.getName(), opts);
                addIfNotNull(document, "channel.fluor", logical.getFluor(), opts);
                addEnumIfNotNull(document, "channel.mode", logical.getMode(), opts);
                addEnumIfNotNull(document, "channel.photometricInterpretation",
                        logical.getPhotometricInterpretation(), opts);
                // Note: length items omitted due to difficulty of handling units
            }
        }
    }

    /**
     * Parses all ownership and time-based details to the index for the given
     * object.
     *
     * @param name
     * @param object
     * @param document
     * @param opts
     */
    public void set_details(final String name, final IObject object,
            final Document document, final LuceneOptions opts) {

        final LuceneOptions stored = new SimpleLuceneOptions(opts, Store.YES);
        final LuceneOptions storedNotAnalyzed = new SimpleLuceneOptions(opts, Index.NOT_ANALYZED, Store.YES);

        Details details = object.getDetails();
        if (details != null) {
            Experimenter e = details.getOwner();
            if (e != null && e.isLoaded()) {
                String omename = e.getOmeName();
                String firstName = e.getFirstName();
                String lastName = e.getLastName();
                add(document, "details.owner.omeName", omename, stored);
                add(document, "details.owner.firstName", firstName, opts);
                add(document, "details.owner.lastName", lastName, opts);
            }

            ExperimenterGroup g = details.getGroup();
            if (g != null && g.isLoaded()) {
                String groupName = g.getName();
                add(document, "details.group.name", groupName, stored);
            }

            Event creationEvent = details.getCreationEvent();
            if (creationEvent != null) {
                add(document, "details.creationEvent.id", creationEvent.getId()
                        .toString(), storedNotAnalyzed);
                if (creationEvent.isLoaded()) {
                    String creation = DateBridge.DATE_SECOND
                            .objectToString(creationEvent.getTime());
                    add(document, "details.creationEvent.time", creation,
                            storedNotAnalyzed);
                }
            }

            Event updateEvent = details.getUpdateEvent();
            if (updateEvent != null) {
                add(document, "details.updateEvent.id", updateEvent.getId()
                        .toString(), storedNotAnalyzed);
                if (updateEvent.isLoaded()) {
                    String update = DateBridge.DATE_SECOND
                            .objectToString(updateEvent.getTime());
                    add(document, "details.updateEvent.time", update,
                            storedNotAnalyzed);
                }
            }

            Permissions perms = details.getPermissions();
            if (perms != null) {
                add(document, "details.permissions", perms.toString(), stored);
            }
        }

    }

    /**
     * Walks the various {@link Folder} instances attached to the object
     * argument so that it may be found via its immediate parent folder.
     */
    public void set_folders(final String name, final IObject object,
            final Document document, final LuceneOptions opts) {
        if (object instanceof Image) {
            final Image image = (Image) object;
            final Iterator<Roi> roiIterator = image.iterateRois();
            while (roiIterator.hasNext()) {
                final Roi roi = roiIterator.next();
                final Iterator<Folder> folderIterator = roi.linkedFolderIterator();
                while (folderIterator.hasNext()) {
                    final Folder folder = folderIterator.next();
                    add(document, "roi.folder.name", folder.getName(), opts);
                }
            }
        }
    }

    /**
     * Walks the {@link Fileset} instances attached to an Image. Fields that are added include:
     *
     * - fileset.entry.clientPath
     * - fileset.entry.name
     * - fileset.templatePrefix
     */
    public void set_fileset(final String name, final IObject object,
                            final Document document, final LuceneOptions opts) {
        if (object instanceof Image) {
            final Image image = (Image) object;
            final Fileset fileset = image.getFileset();
            if (fileset == null) {
                return;
            }
            // Skip fileset indexing above a cut-off
            // As the fileset indexing scales with the number of fileset entries for each
            // images, this operation can quickly lead to performance degradation notable
            // in domains like high-content screening where each of 1K-10K images in a plate
            // can be associated with 10-100K files
            if (fileset.sizeOfUsedFiles() > maxFilesetSize) {
              return;
            }
            add(document, "fileset.templatePrefix", fileset.getTemplatePrefix(), opts);
            final Iterator<FilesetEntry> entryIterator = fileset.iterateUsedFiles();
            while (entryIterator.hasNext()) {
                final FilesetEntry entry = entryIterator.next();
                if (entry == null) {
                    continue;
                }
                add(document, "fileset.entry.clientPath", entry.getClientPath(), opts);
                add(document, "fileset.entry.name", entry.getOriginalFile().getName(), opts);
            }
        }
    }

    /**
     * Loops over each {@link #classes field bridge class} and calls its
     * {@link FieldBridge#set(String, Object, Document, LuceneOptions)}
     * method. Any exceptions are logged but do not cancel execution.
     *
     * @param name
     * @param object
     * @param document
     * @param opts
     */
    public void set_custom(final String name, final IObject object,
            final Document document, final LuceneOptions opts) {

        for (Class<FieldBridge> bridgeClass : classes) {
            if (bridgeClass != null) {
                FieldBridge bridge = null;
                try {
                    bridge = bridgeClass.newInstance();
                    if (bridge instanceof BridgeHelper) {
                        BridgeHelper helper = (BridgeHelper) bridge;
                        helper.setApplicationEventPublisher(publisher);
                    }
                    bridge.set(name, object, document, opts);
                } catch (Exception e) {
                    final String msg = String
                            .format(
                                    "Error calling set on custom bridge type:%s; instance:%s",
                                    bridgeClass, bridge);
                    logger().error(msg, e);
                }
            }
        }

    }

    /**
     * Creates {@link Field} instances for {@link FileAnnotation} objects.
     *
     * @param document
     * @param opts
     * @param fileAnnotation
     */
    private void handleFileAnnotation(final Document document,
            final LuceneOptions opts, FileAnnotation fileAnnotation) {
        OriginalFile file = fileAnnotation.getFile();
        if (file != null) {
            // None of these values can be null
            add(document, "file.name", file.getName(), opts);
            add(document, "file.path", file.getPath(), opts);
            if (file.getHasher() != null) {
                add(document, "file.hasher", file.getHasher().getValue(), opts);
            }
            if (file.getHash() != null) {
                add(document, "file.hash", file.getHash(), opts);
            }
            if (file.getMimetype() != null) {
                add(document, "file.format", file.getMimetype(), opts);
                // ticket:2211 - duplicating for backwards compatibility
                add(document, "file.mimetype", file.getMimetype(), opts);
            }
            addContents(document, "file.contents", file, files, parsers, opts);
        }
    }

    /**
     * Creates {@link Field} instances for {@link MapAnnotation} named-value
     * pair.
     *
     * @param document
     * @param opts
     * @param mapAnnotation
     */
    private void handleMapAnnotation(final Document document,
            final LuceneOptions opts, MapAnnotation mapAnnotation) {
        List<NamedValue> nvs = mapAnnotation.getMapValue();
        if (nvs != null && nvs.size() > 0) {
            for (NamedValue nv : nvs) {
                if (nv != null) {
                    add(document, nv.getName(), nv.getValue(), opts);
                    add(document, "has_key", nv.getName(), opts);
                    add(document, "annotation", nv.getValue(), opts);
                    add(document, "annotation", nv.getName(), opts);
                }
            }
        }
    }
}
