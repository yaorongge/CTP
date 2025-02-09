/*---------------------------------------------------------------
*  Copyright 2005 by the Radiological Society of North America
*
*  This source software is released under the terms of the
*  RSNA Public License (http://mirc.rsna.org/rsnapubliclicense)
*----------------------------------------------------------------*/

package org.rsna.ctp.objects;

import java.awt.Dimension;
import java.awt.geom.*;
import java.awt.Graphics2D;
import java.awt.image.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.*;
import java.util.regex.*;
import javax.imageio.*;
import javax.imageio.stream.*;
import org.apache.log4j.Logger;
import org.dcm4che.data.Dataset;
import org.dcm4che.data.DcmDecodeParam;
import org.dcm4che.data.DcmElement;
import org.dcm4che.data.DcmEncodeParam;
import org.dcm4che.data.DcmObject;
import org.dcm4che.data.DcmObjectFactory;
import org.dcm4che.data.DcmParser;
import org.dcm4che.data.DcmParserFactory;
import org.dcm4che.data.FileFormat;
import org.dcm4che.data.FileMetaInfo;
import org.dcm4che.data.SpecificCharacterSet;
import org.dcm4che.dict.DictionaryFactory;
import org.dcm4che.dict.Status;
import org.dcm4che.dict.TagDictionary;
import org.dcm4che.dict.Tags;
import org.dcm4che.dict.UIDDictionary;
import org.dcm4che.dict.UIDs;
import org.dcm4che.dict.VRs;
import org.rsna.util.*;

/**
 * Class which encapsulates a DICOM object and provides access to its elements.
 */
public class DicomObject extends FileObject {

	static final Logger logger = Logger.getLogger(DicomObject.class);

	static final DcmParserFactory pFact = DcmParserFactory.getInstance();
	static final DcmObjectFactory oFact = DcmObjectFactory.getInstance();
	static final DictionaryFactory dFact = DictionaryFactory.getInstance();
	static final TagDictionary tagDictionary = dFact.getDefaultTagDictionary();
	static final UIDDictionary uidDictionary = dFact.getDefaultUIDDictionary();

	Dataset dataset = null;
	BufferedImage bufferedImage = null;
	int currentFrame = -1;
	boolean isImage = false;
	boolean isManifest = false;
	boolean isAdditionalTFInfo = false;
	boolean isDICOMDIR = false;
	DcmElement directoryRecordSeq = null;
	SpecificCharacterSet charset = null;
	DcmParser parser = null;
	FileFormat fileFormat = null;
	DcmDecodeParam fileParam = null;
	FileMetaInfo fileMetaInfo = null;
	BufferedInputStream in = null;

	/**
	 * Class constructor; parses a file to create a new DicomObject.
	 * This constructor closes the input stream after parsing everything
	 * up to, but not including, the pixel data.
	 * @param file the file containing the DicomObject.
	 * @throws IOException if the file cannot be read or the file does not parse.
	 */
	public DicomObject(File file) throws Exception {
		this(file, false);
	}

	/**
	 * Class constructor; parses a file to create a new DicomObject.
	 * This constructor provides the option of leaving the input stream
	 * open to allow applications to use the parser to obtain the
	 * pixel data and any elements thereafter.
	 * @param file the file containing the DicomObject.
	 * @param leaveOpen true if the input stream is to be left open; false otherwise.
	 * @throws IOException if the file cannot be read or the file does not parse.
	 */
	public DicomObject(File file, boolean leaveOpen) throws Exception {
		super(file);
		try {
			in = new BufferedInputStream(new FileInputStream(file));
			parser = pFact.newDcmParser(in);
			fileFormat = parser.detectFileFormat();
			if (fileFormat == null) {
				throw new IOException("Unrecognized file format: "+file);
			}
			dataset = oFact.newDataset();
			parser.setDcmHandler(dataset.getDcmHandler());

			//Parse the file, but don't get the pixels in order to save heap space.
			parser.parseDcmFile(fileFormat, Tags.PixelData);

			//Get the charset in case we need it for manifest processing.
			charset = dataset.getSpecificCharacterSet();

			//Get the file meta info
			fileMetaInfo = dataset.getFileMetaInfo();
			if (fileMetaInfo == null) {
				//No file meta info; create it and assume IVRLE
				fileMetaInfo = oFact.newFileMetaInfo(dataset, UIDs.ImplicitVRLittleEndian);
			}

			//See if this is a real image.
			isImage = (parser.getReadTag() == Tags.PixelData);

			//Get the decode parameter
			fileParam = parser.getDcmDecodeParam();

			//See if this is a DICOMDIR
			isDICOMDIR = isDICOMDIR();
			directoryRecordSeq = dataset.get(Tags.DirectoryRecordSeq);

			//See if this is a TCE Manifest
			isManifest = checkManifest();

			//See if this is a TCE Additional Teaching File Info document
			isAdditionalTFInfo = checkAdditionalTFInfo();

			if (!leaveOpen) close();
		}
		catch (Exception ex) {
			logger.debug("Exception in constructor", ex);
			close();
			throw ex;
		}
	}

	/**
	 * Close the input stream.
	 */
	public void close() {
		if (in != null) {
			try { in.close(); }
			catch (Exception unable) { logger.warn("Unable to close the input stream"); }
			in = null;
		}
	}

	/**
	 * Get the standard extension for this DicomObject (".dcm" of ".DICOMDIR").
	 * @return ".dcm" or ">DICOMDIR" as appropriate for this object.
	 */
	public String getStandardExtension() {
		return isDICOMDIR ? ".DICOMDIR" : ".dcm";
	}

	/**
	 * Get a prefix for a DicomObject ("DCM-").
	 * @return a prefix for a DicomObject.
	 */
	public String getTypePrefix() {
		return "DCM-";
	}

	/**
	 * Determine whether this file has a typical DICOM filename.
	 * Typical DICOM filenames either end in ".dcm" or are a UID.
	 * The test for the extension is case insensitive.
	 * @return true if the file has a typical DICOM filename; false otherwise.
	 */
	public boolean hasTypicalDicomFilename() {
		return hasTypicalDicomFilename(file.getName());
	}

	/**
	 * Determine whether a filename is a typical DICOM filename.
	 * Typical DICOM filenames either end in ".dcm" or are a UID.
	 * The test for the extension is case insensitive.
	 * @return true if the filename is a typical DICOM filename; false otherwise.
	 */
	public static boolean hasTypicalDicomFilename(String name) {
		String ext = getExtension(name).toLowerCase();
		if (ext.equals(".dcm")) return true;
		if (ext.equals(".dicomdir")) return true;
		if (name.matches("[\\d\\.]+")) return true;
		return false;
	}

	/**
	 * Save the dataset in a file, resetting the input stream position afterward
	 * to where it was before the method call, thus allowing multiple calls to be
	 * made on the same dataset.
	 * @param file the file pointing to where to save the dataset.
	 * @param forceIVRLE true if the syntax of the saved file is to be IVRLE; false if
	 * the syntax is to be determined by the syntax of the dataset. This parameter
	 * is only used if the dataset does not contain encapsulated pixel data.
	 * @throws Exception if the save fails, in which case the input stream is
	 * closed and subsequent calls to this method will fail.
	 */
	public void saveAs(File file, boolean forceIVRLE) throws Exception {
		if (in == null) throw new Exception("Input stream is not open.");
		long streamPosition = parser.getStreamPosition();
		byte[] buffer = new byte[4096];
		FileOutputStream out = null;
		try {
            out = new FileOutputStream(file);

			//Set the encoding
			DcmDecodeParam fileParam = parser.getDcmDecodeParam();
        	String prefEncodingUID = UIDs.ImplicitVRLittleEndian;
			FileMetaInfo fmi = dataset.getFileMetaInfo();
            if ((fmi != null) && (fileParam.encapsulated || !forceIVRLE))
            	prefEncodingUID = fmi.getTransferSyntaxUID();
			DcmEncodeParam encoding = (DcmEncodeParam)DcmDecodeParam.valueOf(prefEncodingUID);
			boolean swap = fileParam.byteOrder != encoding.byteOrder;

            //Create and write the metainfo for the encoding we are using
			fmi = oFact.newFileMetaInfo(dataset, prefEncodingUID);
            dataset.setFileMetaInfo(fmi);
            fmi.write(out);

			//Write the dataset as far as was parsed
			dataset.writeDataset(out, encoding);
			//Write the pixels if the parser actually stopped at the pixeldata
            if (parser.getReadTag() == Tags.PixelData) {
                dataset.writeHeader(
                    out,
                    encoding,
                    parser.getReadTag(),
                    parser.getReadVR(),
                    parser.getReadLength());
                if (encoding.encapsulated) {
                    parser.parseHeader();
                    while (parser.getReadTag() == Tags.Item) {
                        dataset.writeHeader(
                            out,
                            encoding,
                            parser.getReadTag(),
                            parser.getReadVR(),
                            parser.getReadLength());
                        writeValueTo(parser, buffer, out, false);
                        parser.parseHeader();
                    }
                    if (parser.getReadTag() != Tags.SeqDelimitationItem) {
                        throw new Exception(
                            "Unexpected Tag: " + Tags.toString(parser.getReadTag()));
                    }
                    if (parser.getReadLength() != 0) {
                        throw new Exception(
                            "(fffe,e0dd), Length:" + parser.getReadLength());
                    }
                    dataset.writeHeader(
                        out,
                        encoding,
                        Tags.SeqDelimitationItem,
                        VRs.NONE,
                        0);
                }
                else {
                    writeValueTo(parser, buffer, out, swap && (parser.getReadVR() == VRs.OW));
                }
				parser.parseHeader(); //get ready for the next element
			}
			//Now do any elements after the pixels one at a time.
			//This is done to allow streaming of large raw data elements
			//that occur above Tags.PixelData.
			int tag;
			long fileLength = file.length();
			while (!parser.hasSeenEOF()
					&& (parser.getStreamPosition() < fileLength)
						&& ((tag=parser.getReadTag()) != -1)
							&& (tag != 0xFFFCFFFC)) {
				dataset.writeHeader(
					out,
					encoding,
					parser.getReadTag(),
					parser.getReadVR(),
					parser.getReadLength());
				writeValueTo(parser, buffer, out, swap);
				parser.parseHeader();
			}
			out.flush();
			out.close();
			parser.setStreamPosition(streamPosition);
		}
		catch (Exception ex) {
			if (out != null) {
				try { out.close(); }
				catch (Exception unable) { logger.warn("Unable to close the output stream."); }
				file.delete();
				close();
			}
			throw ex;
		}
    }

	//Write out an element value, handling swapping if required.
	private static void writeValueTo(
					DcmParser parser,
					byte[] buffer,
					OutputStream out,
					boolean swap) throws Exception {
		InputStream in = parser.getInputStream();
		int len = parser.getReadLength();
		if (swap && (len & 1) != 0) {
			throw new Exception(
				"Illegal length for swapping value bytes: " + len);
		}
		if (buffer == null) {
			if (swap) {
				int tmp;
				for (int i = 0; i < len; ++i, ++i) {
					tmp = in.read();
					out.write(in.read());
					out.write(tmp);
				}
			} else {
				for (int i = 0; i < len; ++i) {
					out.write(in.read());
				}
			}
		} else {
			byte tmp;
			int c, remain = len;
			while (remain > 0) {
				c = in.read(buffer, 0, Math.min(buffer.length, remain));
				if (c == -1) {
					throw new EOFException("EOF while reading element value");
				}
				if (swap) {
					if ((c & 1) != 0) {
						buffer[c++] = (byte) in.read();
					}
					for (int i = 0; i < c; ++i, ++i) {
						tmp = buffer[i];
						buffer[i] = buffer[i + 1];
						buffer[i + 1] = tmp;
					}
				}
				out.write(buffer, 0, c);
				remain -= c;
			}
		}
		parser.setStreamPosition(parser.getStreamPosition() + len);
	}

	/**
	 * Get a BufferedImage from the DicomObject. This method filters all the pixels for images
	 * with BitsStored < 16, forcing any pixels with overlay bits to the maximum allowed pixel
	 * value (2^BitsStored - 1). This is done to protect the JPEG converter, which throws an
	 * array out of bounds exception on such pixels.
	 * @param frame the frame to load (the first frame is zero).
	 * @param forceReload true if the image is to be reloaded even if it is already loaded;
	 * false if the image is only to be loaded if necessary.
	 * @return the BufferedImage after burning in the overlays.
	 * @throws Exception if the image could not be loaded.
	 */
	public synchronized BufferedImage getBufferedImage(int frame, boolean forceReload) throws Exception {
		if (!isImage) throw new IOException("Not an image: "+file);
		if (!forceReload && (bufferedImage != null) && (currentFrame == frame)) return bufferedImage;

		bufferedImage = null;
		currentFrame = -1;
		FileImageInputStream fiis = null;
		ImageReader reader = null;
		try {
			fiis = new FileImageInputStream(file);
			reader = (ImageReader)ImageIO.getImageReadersByFormatName("DICOM").next();
			reader.setInput(fiis);
			bufferedImage = reader.read(frame);
		}
		catch (Exception ex) { logger.warn("Unable to read the image", ex); }
		finally {
			try { if (fiis != null) fiis.close(); }
			catch (Exception ignore) { }
			try { if (reader != null) reader.dispose(); }
			catch (Exception ignore) { }
		}
		if (bufferedImage == null) throw new Exception("Could not read "+file);

		burnInOverlays();
		currentFrame = frame;
		return bufferedImage;
	}

	//Burn in the overlays to keep the JPEG converter
	//from throwing an array out of bounds exception
	private void burnInOverlays() {
		if ((getSamplesPerPixel() == 1) && (getPlanarConfiguration() == 0)) {
			int bitsStored = getBitsStored();
			if ((bitsStored > 8) && (bitsStored < 16)) {
				WritableRaster wr = bufferedImage.getRaster();
				DataBuffer b = wr.getDataBuffer();
				if (b.getDataType() == DataBuffer.TYPE_USHORT) {
					int maxPixel = (1 << bitsStored) - 1;
					int highBitsMask = 0xffff & (maxPixel ^ -1);
					DataBufferUShort bs = (DataBufferUShort)b;
					short[] data = bs.getData();
					for (int i=0; i<data.length; i++) {
						if ((data[i] & highBitsMask) != 0) data[i] = (short)maxPixel;
					}
				}
			}
		}
	}

	/**
	 * Get a BufferedImage scaled in accordance with the size rules for saveAsJPEG.
	 * @param maxSize the maximum width of the created JPEG;
	 * @param minSize the minimum width of the created JPEG;
	 * @return a Buffered Image scaled to the required size, or null if the image
	 * could not be created.
	 */
	public synchronized BufferedImage getScaledBufferedImage(int frame, int maxSize, int minSize) {

		int maxCubic = 1100; //The maximum dimension for which bicubic interpolation is done.
		try {
			//Check that all is well
			getBufferedImage(frame, false);
			if (bufferedImage == null) return null;
			int width = bufferedImage.getWidth();
			int height = bufferedImage.getHeight();
			if (minSize > maxSize) minSize = maxSize;

			// Set the scale.
			double scale = 1.0D;
			double minScale = (double)minSize/(double)width;
			double maxScale = (double)maxSize/(double)width;

			if (width >= minSize)
				scale = (width > maxSize) ? maxScale : 1.0D;
			else
				scale = minScale;

			int pixelSize = bufferedImage.getColorModel().getPixelSize();

			// Set up the transform
			AffineTransform at = AffineTransform.getScaleInstance(scale, scale);
			AffineTransformOp atop;

			if ((pixelSize == 8) || (width > maxCubic) || (height > maxCubic)) {
				atop = new AffineTransformOp(at, AffineTransformOp.TYPE_NEAREST_NEIGHBOR);
			}
			else {
				atop = new AffineTransformOp(at, AffineTransformOp.TYPE_BICUBIC);
			}

			// Make a destination image
			BufferedImage scaledImage =
							new BufferedImage(
									(int)(width*scale),
									(int)(height*scale),
									BufferedImage.TYPE_INT_RGB);

			// Paint the transformed image.
			Graphics2D g2d = scaledImage.createGraphics();
			g2d.drawImage(bufferedImage, atop, 0, 0);
			g2d.dispose();
			return scaledImage;
		}
		catch (Exception e) {
			logger.warn("Unable to get Scaled Buffered Image",e);
			return null;
		}
	}

	/**
	 * Save the specified frame as a JPEG, scaling it to a specified size
	 * and using the specified quality setting.
	 * @param file the file into which to write the encoded image.
	 * @param frame the frame to save (the first frame is zero).
	 * @param maxSize the maximum width of the created JPEG.
	 * @param minSize the minimum width of the created JPEG.
	 * @param quality the quality parameter, ranging from 0 to 100;
	 * a negative value uses the default setting supplied by by ImageIO.
	 * @return the dimensions of the JPEG that was created, or null if an error occurred.
	 */
	public Dimension saveAsJPEG(File file, int frame, int maxSize, int minSize, int quality) {
		FileImageOutputStream out = null;
		ImageWriter writer = null;
		Dimension result = null;
		try {
			BufferedImage scaledImage = getScaledBufferedImage(frame, maxSize, minSize);
			if (scaledImage == null) return null;
			result = new Dimension(scaledImage.getWidth(), scaledImage.getHeight());

			// JPEG-encode the image and write it in the specified file.
			writer = ImageIO.getImageWritersByFormatName("jpeg").next();
			ImageWriteParam iwp = writer.getDefaultWriteParam();
			if (quality >= 0) {
				quality = Math.min(quality, 100);
				float fQuality = ((float)quality) / 100.0F;
				iwp.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
				iwp.setCompressionQuality(fQuality);
			}
			out = new FileImageOutputStream(file);
			writer.setOutput(out);
			IIOImage image = new IIOImage(scaledImage, null, null);
			writer.write(null, image, iwp);
		}
		catch (Exception ex) { result = null; logger.warn("Unable to save the image as a JPEG", ex); }
		finally {
			if (out != null) {
				try { out.flush(); out.close(); }
				catch (Exception ignore) { }
			}
			if (writer != null) writer.dispose();
		}
		return result;
	}

	/**
	 * Save the specified frame as a JPEG, scaling it to a specified size,
	 * using the specified quality setting, and setting the window level and width.
	 * This method only operates on images with bitsStored values from 12 through 16.
	 * This method only operates on images with ColorModel pixel sizes of 16 bits or less.
	 * All other sizes are returned without window level and width processing.
	 * @param file the file into which to write the encoded image.
	 * @param w the width of the saved image, or -1 to use the original image width.
	 * @param h the height of the saved image, or -1 to use the original image height.
	 * @param frame the frame to save (the first frame is zero).
	 * @param windowLevel the window level in display values (e.g. Hounsfield values).
	 * @param windowWidth the window width in display values.
	 * @return the dimensions of the JPEG that was created, or null if an error occurred.
	 */
	public Dimension saveAsWindowLeveledJPEG(File file, int w, int h, int frame, int quality, int windowLevel, int windowWidth) {
		int maxCubic = 1100; //The maximum dimension for which bicubic interpolation is done.
		FileImageOutputStream out = null;
		ImageWriter writer = null;
		Dimension result = null;
		try {

			BufferedImage originalImage = getBufferedImage(frame, false);
			if (originalImage == null) return null;

			// Set the scale for the output image.
			int origW = originalImage.getWidth();
			int origH = originalImage.getHeight();
			double imageScale = 1.0;
			if ((w == -1) || (h == -1)) {
				w = origW;
				h = origH;
			}
			else {
				imageScale = (double)w / (double)origW;
			}

			result = new Dimension(w, h);

			//See whether the LUT is inverted
			String lutShape = getElementValue("PresentationLUTShape").toLowerCase().trim();
			boolean inverse = lutShape.equals("inverse");

			//Get the pixel representation
			boolean isUnsigned = !getElementValue("PixelRepresentation").trim().equals("1");

			//Convert from display units to pixel units.
			//windowLevel and windowWidth are in display units.
			//The conversion is: (DisplayUnit) = (RescaleSlope) * (PixelUnit) + (RescaleIntercept)
			//so we have to invert this relationship to get the pixel units.
			//To make clear in what domain the arithmetic is being done, we explicitly cast the values:
			float slope = getFloat("RescaleSlope", 1.0f);
			float intercept = getFloat("RescaleIntercept", 0.0f);
			windowLevel = (int)( ( (float)windowLevel - intercept ) / slope );
			windowWidth = (int)( (float)windowWidth / slope );

			//Do the window level/width operation, if possible.
			int bitsStored = getBitsStored();
			int cmPixelSize = originalImage.getColorModel().getPixelSize();
			if ((bitsStored >= 8) && (bitsStored <= 16) && (cmPixelSize <= 16)) {
				int size = 1 << bitsStored;
				byte[] rgb = new byte[size];

				if (windowWidth < 2) windowWidth = 2;
				int bottom = windowLevel - windowWidth/2;
				int top = bottom + windowWidth;
				bottom = Math.min( Math.max(0, bottom), size-1 );
				top = Math.max( Math.min(size-1, top), 0 );
				if (!inverse) {
					if (bottom > 0) Arrays.fill(rgb, 0, bottom-1, (byte)0);
					if (top < size-1) Arrays.fill(rgb, top, size-1, (byte)255);
					double scale = 255.0 / ((double)(top - bottom));
					for (int i=Math.max(bottom, 0); i<Math.min(top, size); i++) {
						rgb[i] = (byte)(scale * (i - bottom));
					}
				}
				else {
					if (bottom > 0) Arrays.fill(rgb, 0, bottom-1, (byte)255);
					if (top < size-1) Arrays.fill(rgb, top, size-1, (byte)0);
					double scale = 255.0 / ((double)(top - bottom));
					for (int i=Math.max(bottom, 0); i<Math.min(top, size); i++) {
						rgb[i] = (byte)(255 - (int)(scale * (i - bottom)));
					}
				}
				if (!isUnsigned) { //blank out the negative pixel values
					Arrays.fill(rgb, size/2 + 1, size-1, (byte)0);
				}
				ColorModel cm = new IndexColorModel(cmPixelSize, size, rgb, rgb, rgb);
				originalImage = new BufferedImage( cm, originalImage.getRaster(), false, null);
			}

			// Make a destination image with the original resolution,
			// but with 8-bit pixels so we can convert the result to JPEG.
			BufferedImage rgbImage = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);

			// Set up the transform
			AffineTransform at;
			if (imageScale == 1.0d) at = new AffineTransform(); //identity transform
			else at = AffineTransform.getScaleInstance(imageScale, imageScale);
			int pixelSize = originalImage.getColorModel().getPixelSize();
			AffineTransformOp atop;
			if ((pixelSize == 8) || (w > maxCubic) || (h > maxCubic) )
				atop = new AffineTransformOp(at,AffineTransformOp.TYPE_NEAREST_NEIGHBOR);
			else
				atop = new AffineTransformOp(at,AffineTransformOp.TYPE_BICUBIC);

			// Paint the original (possibly window leveled) image onto the RGB image.
			Graphics2D g2d = rgbImage.createGraphics();
			g2d.drawImage(originalImage, atop, 0, 0);
			g2d.dispose();

			// JPEG-encode the image and write it in the specified file.
			writer = ImageIO.getImageWritersByFormatName("jpeg").next();
			ImageWriteParam iwp = writer.getDefaultWriteParam();
			if (quality >= 0) {
				quality = Math.min(quality,100);
				float fQuality = ((float)quality) / 100.0F;
				iwp.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
				iwp.setCompressionQuality(fQuality);
			}
			out = new FileImageOutputStream(file);
			writer.setOutput(out);
			IIOImage image = new IIOImage(rgbImage, null, null);
			writer.write(null, image, iwp);
		}
		catch (Exception ex) { result = null; logger.warn("Unable to save the image as a JPEG", ex); }
		finally {
			if (out != null) {
				try { out.flush(); out.close(); }
				catch (Exception ignore) { }
			}
			if (writer != null) writer.dispose();
		}
		return result;
	}

	/**
	 * Get the Transfer Syntax UID.
	 * @return the TransferSyntaxUID from the file metadata,
	 * or null if the metadata does not exist (e.g., the file
	 * is not a Part 10 file).
	 */
	public String getTransferSyntaxUID() {
		try { return fileMetaInfo.getTransferSyntaxUID(); }
		catch (Exception noMetadata) { return null; }
	}

	/**
	 * Get the transfer syntax name.
	 * @return the name of the transfer syntax.
	 */
	public String getTransferSyntaxName() {
		String transferSyntaxUID = getTransferSyntaxUID();
		try { return uidDictionary.lookup(transferSyntaxUID).name; }
		catch (Exception e) {
			return "Unknown transfer syntax: " + transferSyntaxUID;
		}
	}

	/**
	 * Get the DcmParser.
	 * @return the parser used to parse the object.
	 */
	public DcmParser getDcmParser() {
		return parser;
	}

	/**
	 * Get the FileFormat.
	 * @return the FileFormat acquired when the object was parsed.
	 */
	public FileFormat getFileFormat() {
		return fileFormat;
	}

	/**
	 * Get the DcmDecodeParam.
	 * @return the DcmDecodeParam acquired when the object was parsed.
	 */
	public DcmDecodeParam getDcmDecodeParam() {
		return fileParam;
	}

	/**
	 * Get the FileMetaInfo.
	 * @return the FileMetaInfo acquired when the object was parsed.
	 */
	public FileMetaInfo getFileMetaInfo() {
		return fileMetaInfo;
	}

	/**
	 * Get the Dataset.
	 * @return the Dataset containing all the elements up to the pixel data.
	 */
	public Dataset getDataset() {
		return dataset;
	}

	/**
	 * Get the SOP Class name. See getSOPClassUID for the rules
	 * used to obtain the SOP Class UID that is used as the key
	 * in the UID dictionary for obtaining the name.
	 * @return the name of the SOP Class, or "Unknown SOP Class"
	 * if the UID or the name cannot be found.
	 */
	public String getSOPClassName() {
		String sopClassUID = null;
		try {
			sopClassUID = getSOPClassUID();
			return uidDictionary.lookup(sopClassUID).name;
		}
		catch (Exception noSOPClass) {
			return "Unknown SOP Class: " + sopClassUID;
		}
	}

	/**
	 * Get the array of ints identifying an element.
	 * To specify an element in an SQ item dataset,
	 * tags must be separated by "::".
	 * If no tagString is specified, return an empty int array.
	 * @param tagString the list of tags identifying an element
	 */
	public static int[] getTagArray(String tagString) {
		tagString = tagString.trim();
		if (tagString.equals("")) return new int[0];
		String[] tagNames = tagString.split("::");
		int[] tagInts = new int[tagNames.length];
		for (int i=0; i<tagNames.length; i++) {
			tagInts[i] = getElementTag(tagNames[i]);
		}
		return tagInts;
	}

	static final Pattern hexPattern = Pattern.compile("([0-9a-fA-F]{1,8})");
	static final Pattern hexCommaPattern = Pattern.compile("([0-9a-fA-F]{0,4}),([0-9a-fA-F]{1,4})");
	/**
	 * Get the tag for a DICOM element. This
	 * method supports dcm4che names as well as hex strings,
	 * with or without enclosing parentheses or square brackets
	 * and with or without a comma separating the group and the
	 * element numbers. Examples of element specifications are:
	 *<ul>
	 *<li>100020
	 *<li>00100020
	 *<li>[00100020]
	 *<li>(00100020)
	 *<li>0010,0020
	 *<li>10,20
	 *<li>(10,20)
	 *<li>[10,20]
	 *</ul>
	 * @param name the dcm4che element name or the coded hex value.
	 * @return the tag, or zero if the name is not a parsable element specification.
	 */
	public static int getElementTag(String name) {
		if (name == null) return 0;
		name = name.trim();

		//Try it as a dcm4che element name
		try { return Tags.forName(name); }
		catch (Exception notInDictionary) { }

		//Not a name, set up to parse it as a hex specification
		int k = name.length() - 1;
		if (name.startsWith("[") && name.endsWith("]")) name = name.substring(1, k).trim();
		else if (name.startsWith("(") && name.endsWith(")")) name = name.substring(1, k).trim();

		//First try it as a pure hex integer, with no comma between the group and element
		Matcher matcher = hexPattern.matcher(name);
		if (matcher.matches()) {
			return StringUtil.getHexInt(matcher.group(1));
		}

		//No luck there; try it as two comma-separated hex integers
		matcher = hexCommaPattern.matcher(name);
		if (matcher.matches()) {
			int group = StringUtil.getHexInt(matcher.group(1));
			int elem = StringUtil.getHexInt(matcher.group(2));
			return (group << 16) | (elem & 0xFFFF);
		}

		//Not a valid specification
		return 0;
	}

	/**
	 * Get the dcm4che name of a DICOM element.
	 * @param tag
	 * @return the dcm4che tag name.
	 */
	public static String getElementName(int tag) {
		TagDictionary.Entry entry = tagDictionary.lookup(tag);
		if (entry == null) return null;
		return entry.name.replaceAll("'s\\s", "").replaceAll("[^a-zA-Z0-9]", "");
	}

	/**
	 * Get the (group,element) number of a DICOM element.
	 * @param tag
	 * @return the tag dictionary string for the tag.
	 */
	public static String getElementNumber(int tag) {
		return Tags.toString(tag);
	}

	/**
	 * Get the contents of a DICOM element in the DicomObject's dataset
	 * as a floating point number
	 * @param tagName the dcm4che name of the element.
	 * @return the value of the element as a floating point number.
	 */
	public float getFloat(String tagName) {
		return getFloat(tagName, 0.0F);
	}

	/**
	 * Get the contents of a DICOM element in the DicomObject's dataset
	 * as a floating point number
	 * @param tagName the dcm4che name of the element.
	 * @param defaultValue the value returned if the object does not have the element.
	 * @return the value of the element as a floating point number.
	 */
	public float getFloat(String tagName, float defaultValue) {
		return getFloat(getElementTag(tagName), defaultValue);
	}

	/**
	 * Get the contents of a DICOM element in the DicomObject's dataset
	 * as a floating point number
	 * @param tag the identifier of the element.
	 * @param defaultValue the value returned if the object does not have the element.
	 * @return the value of the element as a floating point number.
	 */
	public float getFloat(int tag, float defaultValue) {
		try { return dataset.getFloat(tag, defaultValue); }
		catch (Exception e) { return defaultValue; }
	}

	/**
	 * Get the ByteBuffer of a DICOM element in the DicomObject's dataset.
	 * @param tag the group and element number of the element.
	 * @return the value of the element.
	 */
	public ByteBuffer getElementByteBuffer(int tag) {
		return dataset.getByteBuffer(tag);
	}

	/**
	 * Get the contents of a DICOM element in the DicomObject's dataset.
	 * This method returns an empty String if the element does not exist.
	 * @param tagName the dcm4che name of the element.
	 * @return the text of the element, or the empty String if the
	 * element does not exist.
	 */
	public String getElementValue(String tagName) {
		return getElementValue(getTagArray(tagName), "");
	}

	/**
	 * Get the contents of a DICOM element in the DicomObject's dataset.
	 * The value of the tagName argument can be a dcm4che element name
	 * (e.g., SOPInstanceUID), or the tag itself, coded either as (0008,0018)
	 * or [0008,0018]. This method returns the defaultString argument if
	 * the element does not exist.
	 * @param tagName the dcm4che name of the element.
	 * @param defaultString the String to return if the element does not exist.
	 * @return the text of the element, or defaultString if the element does not exist.
	 */
	public String getElementValue(String tagName, String defaultString) {
		return getElementValue(getTagArray(tagName), defaultString);
	}

	/**
	 * Get the contents of a DICOM element in the DicomObject's dataset.
	 * This method returns an empty String if the element does not exist.
	 * @param tag the tag specifying the element (in the form 0xggggeeee).
	 * @return the text of the element, or the empty String if the
	 * element does not exist.
	 */
	public String getElementValue(int tag) {
		return getElementValue(tag, "");
	}

	/**
	 * Get the contents of a DICOM element in the DicomObject's dataset.
	 * If the element is part of a private group owned by CTP, it returns the
	 * value as text. This method returns the defaultString argument if the
	 * element does not exist.
	 * @param tag the tag specifying the element (in the form 0xggggeeee).
	 * @param defaultString the String to return if the element does not exist.
	 * @return the text of the element, or defaultString if the element does not exist.
	 */
	public String getElementValue(int tag, String defaultString) {
		return getElementValue(dataset, tag, defaultString);
	}

	//Do the actual work of getting an element value from a dataset
	private String getElementValue(Dataset dataset, int tag, String defaultString) {

		//Handle FileMetaInfo references
		if ((fileMetaInfo != null) && ((tag & 0x7FFFFFFF) < 0x80000)) {
			DcmElement el = fileMetaInfo.get(tag);
			if (el == null) return defaultString;
			try {
				String value = el.getString(charset);
				if (value == null) return defaultString;
				return value;
			}
			catch (Exception returnDefault) { }
			return defaultString;
		}

		//Not FMI, handle DataSet references
		boolean ctp = false;
		if (((tag & 0x00010000) != 0) && ((tag & 0x0000ff00) != 0)) {
			int blk = (tag & 0xffff0000) | ((tag & 0x0000ff00) >> 8);
			try { ctp = dataset.getString(blk).equals("CTP"); }
			catch (Exception notCTP) { ctp = false; }
		}
		String value = null;
		try {
			if (ctp) {
				value = new String(dataset.getByteBuffer(tag).array());
			}
			else {
				DcmElement el = dataset.get(tag);
				String[] s = el.getStrings(charset);
				if (s.length == 1) return s[0];
				if (s.length == 0) return "";
				StringBuffer sb = new StringBuffer( s[0] );
				for (int i=1; i<s.length; i++) {
					sb.append( "\\" + s[i] );
				}
				return sb.toString();
			}
		}
		catch (Exception notAvailable) { value = null; }
		if (value == null) value = defaultString;
		return value;
	}

	/**
	 * Get the contents of a DICOM element in the DicomObject's dataset as a
	 * byte array. This method supports accessing the item datasets of SQ elements,
	 * but it only searches the first item dataset at each level.
	 * It returns null if the element cannot be obtained.
	 * @param tags the sequence of tags specifying the element (in the form 0xggggeeee),
	 * where all the tags but the last must refer to an SQ element.
	 * @return the text of the element, or the empty string
	 * if the element does not exist.
	 */
	public String getElementValue(int[] tags) {
		return getElementValue(tags, "");
	}

	/**
	 * Get the contents of a DICOM element in the DicomObject's dataset as a
	 * byte array. This method supports accessing the item datasets of SQ elements,
	 * but it only searches the first item dataset at each level.
	 * It returns null if the element cannot be obtained.
	 * @param tags the sequence of tags specifying the element (in the form 0xggggeeee),
	 * where all the tags but the last must refer to an SQ element.
	 * @return the text of the element, or the empty string
	 * if the element does not exist.
	 */
	public String getElementValue(int[] tags, String defaultString) {
		try {
			if (tags.length == 0) return defaultString;

			DcmElement de = null;
			Dataset ds = dataset;
			//Walk the SQ datasets to get to the last one
			for (int k=0; k<tags.length-1; k++) {
				de = ds.get(tags[k]);
				if (de == null) return defaultString;
				if (!VRs.toString(de.vr()).equals("SQ")) return defaultString;
				ds = de.getItem(0);
				if (ds == null) return defaultString;
			}
			//Now get the element specified by the last tag
			return getElementValue(ds, tags[tags.length -1], defaultString);
		}
		catch (Exception e) { return defaultString; }
	}

	/**
	 * Get the contents of the first element found in an SQ element's item datasets.
	 * @param el the SQ element to search.
	 * @param tag the tag specifying the element to find in the SQ element's item datasets.
	 * @param defaultString the String to return if the element does not exist.
	 * @return the text of the element, or defaultString if the element cannot be found
	 * in any of the SQ element's item datasets.
	 */
	public String getElementValueFromSQ(DcmElement el, int tag, String defaultString) {
		Dataset sq;
		try {
			if ((el != null) && (el.vr() == VRs.SQ)) {
				int item = 0;
				while ((sq=el.getItem(item++)) != null) {
					DcmElement e = sq.get(tag);
					if (e != null) {
						String[] s = e.getStrings(charset);
						if (s.length == 1) return s[0];
						if (s.length == 0) return "";
						StringBuffer sb = new StringBuffer( s[0] );
						for (int i=1; i<s.length; i++) {
							sb.append( "\\" + s[i] );
						}
						return sb.toString();
					}
				}
			}
		}
		catch (Exception ex) { }
		return defaultString;
	}

	//Private method to find the DicomElement referenced by a tag array.
	//This method is used by getElementBytes and getElementString
	private DcmElement getElement(int[] tags) {
		DcmElement de = null;
		try {
			if (tags.length == 0) return null;
			Dataset ds = dataset;
			//Walk the SQ datasets to get to the last one
			for (int k=0; k<tags.length-1; k++) {
				de = ds.get(tags[k]);
				if (de == null) return null;
				if (!VRs.toString(de.vr()).equals("SQ")) return null;
				ds = de.getItem(0);
				if (ds == null) return null;
			}
			//Now get the element specified by the last tag
			int tag = tags[tags.length -1];
			if ((fileMetaInfo != null) && (tag & 0x7FFFFFFF) < 0x80000) {
				de = fileMetaInfo.get(tag);
			}
			else de = ds.get(tag);
		}
		catch (Exception keepNull) { }
		return de;
	}

	/**
	 * Get the contents of a DICOM element in the DicomObject's dataset as a
	 * byte array. This method supports accessing the item datasets of SQ elements,
	 * but it only searches the first item dataset at each level.
	 * It returns null if the element cannot be obtained.
	 * @param tags the sequence of tags specifying the element (in the form 0xggggeeee),
	 * where all the tags but the last must refer to an SQ element.
	 * @return the byte array containing the value of the element, or null
	 * if the element does not exist.
	 */
	public byte[] getElementBytes(int[] tags) {
		try {
			DcmElement de = getElement(tags);
			if (de == null) return null;
			int len = de.length();
			ByteBuffer bb = de.getByteBuffer();
			byte[] bytes = new byte[len];
			for (int i=0; i<len; i++) bytes[i] = bb.get(i);
			return bytes;
		}
		catch (Exception e) { return null; }
	}

	/**
	 * Get the contents of a DICOM element in the DicomObject's dataset as a
	 * byte array. This method returns null if the element does not exist.
	 * @param tag the tag specifying the element (in the form 0xggggeeee).
	 * @return the byte array containing the value of the element, or null
	 * if the element does not exist.
	 */
	public byte[] getElementBytes(int tag) {
		try {
			DcmElement de = dataset.get(tag);
			if (de == null) return null;
			int len = de.length();
			ByteBuffer bb = de.getByteBuffer();
			byte[] bytes = new byte[len];
			for (int i=0; i<len; i++) bytes[i] = bb.get(i);
			return bytes;
		}
		catch (Exception e) { return null; }
	}

	/**
	 * Get the contents of a DICOM element in the DicomObject's dataset as a
	 * String. This method supports accessing the item datasets of SQ elements,
	 * but it only searches the first item dataset at each level.
	 * It returns null if the element cannot be obtained.
	 * @param tags the sequence of tags specifying the element (in the form 0xggggeeee),
	 * where all the tags but the last must refer to an SQ element.
	 * @return the String value of the element, or the empty string
	 * if the element does not exist.
	 */
	public String getElementString(int[] tags) {
		try {
			DcmElement de = getElement(tags);
			if (de == null) return "";
			String[] s = de.getStrings(charset);
			if (s.length == 1) return s[0];
			if (s.length == 0) return "";
			StringBuffer sb = new StringBuffer( s[0] );
			for (int i=1; i<s.length; i++) {
				sb.append( "|" + s[i] );
			}
			return sb.toString();
		}
		catch (Exception e) { return ""; }
	}

	/**
	 * Set the contents of a DICOM element in the DicomObject's dataset.
	 * This method works around the bug in dcm4che which inserts the wrong
	 * VR (SH) when storing an empty element of VR = PN. It also handles the
	 * problem in older dcm4che versions which threw an exception when an
	 * empty DA element was created. And finally, it forces the VR of private
	 * elements to UT.
	 * @param tagName the dcm4che tag name of the element.
	 * @param value the text value to set in the element.
	 */
	public void setElementValue(String tagName, String value) throws Exception {
		setElementValue(Tags.forName(tagName),value);
	}

	/**
	 * Set the contents of a DICOM element in the DicomObject's dataset.
	 * This method works around the bug in dcm4che which inserts the wrong
	 * VR (SH) when storing an empty element of VR = PN. It also handles the
	 * problem in older dcm4che versions which threw an exception when an
	 * empty DA element was created. And finally, it forces the VR of private
	 * elements to UT.
	 * @param tag the tag specifying the element (in the form 0xggggeeee).
	 * @param value the text value to set in the element.
	 */
	public void setElementValue(int tag, String value) throws Exception {
		if ((tag&0x10000) != 0) dataset.putUT(tag,value);
		else {
			int vr = 0;
			TagDictionary.Entry entry = tagDictionary.lookup(tag);
			try { vr = VRs.valueOf(entry.vr); }
			catch (Exception ex) { vr = VRs.valueOf("UT"); }
			if ((value == null) || value.equals("")) {
				if (vr == VRs.PN) dataset.putXX(tag,vr," ");
				else dataset.putXX(tag,vr);
			}
			else dataset.putXX(tag,vr,value);
		}
	}

	/**
	 * Convenience method to get the contents of the PatientName element.
	 * If the DicomObject is a DICOMDIR, the DirectoryRecordSeq element
	 * is searched for the first PatientName element.
	 * @return the text of the element or the empty String if the
	 * element does not exist.
	 */
	public String getPatientName() {
		return isDICOMDIR ?
					getElementValueFromSQ(directoryRecordSeq, Tags.PatientName, "")
							: getElementValue(Tags.PatientName);
	}

	/**
	 * Convenience method to get the contents of the PatientID element.
	 * If the DicomObject is a DICOMDIR, the DirectoryRecordSeq element
	 * is searched for the first PatientID element.
	 * @return the text of the element or the empty String if the
	 * element does not exist.
	 */
	public String getPatientID() {
		return isDICOMDIR ?
					getElementValueFromSQ(directoryRecordSeq, Tags.PatientID, "")
							: getElementValue(Tags.PatientID);
	}

	/**
	 * Convenience method to get the contents of the AccessionNumber element.
	 * If the DicomObject is a DICOMDIR, the DirectoryRecordSeq element
	 * is searched for the first AccessionNumber element.
	 * @return the text of the element or the empty String if the
	 * element does not exist.
	 */
	public String getAccessionNumber() {
		return isDICOMDIR ?
					getElementValueFromSQ(directoryRecordSeq, Tags.AccessionNumber, "")
							: getElementValue(Tags.AccessionNumber);
	}

	/**
	 * Convenience method to get the contents of the BodyPartExamined element.
	 * @return the text of the element or the empty String if the
	 * element does not exist.
	 */
	public String getBodyPartExamined() {
		return getElementValue(Tags.BodyPartExamined);
	}

	/**
	 * Convenience method to get the contents of the StudyDescription element.
	 * @return the text of the element or the empty String if the
	 * element does not exist.
	 */
	public String getStudyDescription() {
		return getElementValue(Tags.StudyDescription);
	}

	/**
	 * Convenience method to get the contents of the Modality element.
	 * If the DicomObject is a DICOMDIR, the DirectoryRecordSeq element
	 * is searched for the first Modality element.
	 * @return the text of the element or the empty String if the
	 * element does not exist.
	 */
	public String getModality() {
		return isDICOMDIR ?
					getElementValueFromSQ(directoryRecordSeq, Tags.Modality, "")
							: getElementValue(Tags.Modality);
	}

	/**
	 * Convenience method to get the contents of the SeriesNumber element.
	 * @return the text of the element or the empty String if the
	 * element does not exist.
	 */
	public String getSeriesNumber() {
		return getElementValue(Tags.SeriesNumber);
	}

	/**
	 * Convenience method to get the contents of the AcquisitionNumber element.
	 * @return the text of the element or the empty String if the
	 * element does not exist.
	 */
	public String getAcquisitionNumber() {
		return getElementValue(Tags.AcquisitionNumber);
	}

	/**
	 * Convenience method to get the contents of the InstanceNumber element.
	 * @return the text of the element or the empty String if the
	 * element does not exist.
	 */
	public String getInstanceNumber() {
		return getElementValue(Tags.InstanceNumber);
	}

	/**
	 * Convenience method to get the contents of the SOPClassUID element.
	 * If the DicomObject is a DICOMDIR, the contents of the
	 * MediaStorageSOPClassUID are used as the SOPClassUID.
	 * @return the text of the element or null if the element does not exist.
	 */
	public String getSOPClassUID() {
		return isDICOMDIR ?
					getMediaStorageSOPClassUID()
							: getElementValue(Tags.SOPClassUID, null);
	}

	/**
	 * Convenience method to get the contents of the MediaStorageSOPClassUID element.
	 * @return the text of the element or null if the element does not exist.
	 */
	public String getMediaStorageSOPClassUID() {
		return fileMetaInfo.getMediaStorageSOPClassUID();
	}

	/**
	 * Convenience method to get the contents of the SOPInstanceUID element.
	 * If the DicomObject is a DICOMDIR, the contents of the
	 * MediaStorageSOPInstanceUID are used as the SOPInstanceUID.
	 * @return the text of the element or null if the element does not exist.
	 */
	public String getSOPInstanceUID() {
		return isDICOMDIR ?
					getMediaStorageSOPInstanceUID()
							: getElementValue(Tags.SOPInstanceUID, null);
	}

	/**
	 * Convenience method to get the contents of the SOPInstanceUID element.
	 * @return the text of the element or null if the element does not exist.
	 */
	public String getMediaStorageSOPInstanceUID() {
		return fileMetaInfo.getMediaStorageSOPInstanceUID();
	}

	/**
	 * Convenience method to get the contents of the SOPInstanceUID element.
	 * Included for compatibility with other FileObjects.
	 * @return the text of the element or null if the element does not exist.
	 */
	public String getUID() {
		return getSOPInstanceUID();
	}

	/**
	 * Convenience method to get the contents of the StudyDate element.
	 * If the DicomObject is a DICOMDIR, the DirectoryRecordSeq element
	 * is searched for the first StudyDate element.
	 * @return the text of the element or null if the element does not exist.
	 */
	public String getStudyDate() {
		return isDICOMDIR ?
					getElementValueFromSQ(directoryRecordSeq, Tags.StudyDate, null)
							: getElementValue(Tags.StudyDate, null);
	}

	/**
	 * Convenience method to get the contents of the StudyTime element.
	 * If the DicomObject is a DICOMDIR, the DirectoryRecordSeq element
	 * is searched for the first StudyTime element.
	 * @return the text of the element or null if the element does not exist.
	 */
	public String getStudyTime() {
		return isDICOMDIR ?
					getElementValueFromSQ(directoryRecordSeq, Tags.StudyTime, null)
							: getElementValue(Tags.StudyTime, null);
	}

	/**
	 * Convenience method to get the contents of the StudyInstanceUID element.
	 * If the DicomObject is a DICOMDIR, the DirectoryRecordSeq element
	 * is searched for the first StudyInstanceUID element.
	 * @return the text of the element or null if the element does not exist.
	 */
	public String getStudyInstanceUID() {
		return isDICOMDIR ?
					getElementValueFromSQ(directoryRecordSeq, Tags.StudyInstanceUID, null)
							: getElementValue(Tags.StudyInstanceUID, null);
	}

	/**
	 * Convenience method to get the contents of the StudyInstanceUID element.
	 * Included for compatibility with other FileObjects.
	 * @return the text of the element or null if the element does not exist.
	 */
	public String getStudyUID() {
		return getStudyInstanceUID();
	}

	/**
	 * Convenience method to get the contents of the SeriesInstanceUID element.
	 * @return the text of the element or the empty string if the element does not exist.
	 */
	public String getSeriesInstanceUID() {
		return getElementValue(Tags.SeriesInstanceUID);
	}

	/**
	 * Convenience method to get the contents of the SeriesInstanceUID element.
	 * Included for compatibility with other FileObjects.
	 * @return the text of the element or null if the element does not exist.
	 */
	public String getSeriesUID() {
		return getSeriesInstanceUID();
	}

	/**
	 * Convenience method to get the contents of the SeriesDescription element.
	 * @return the text of the element or the empty string if the element does not exist.
	 */
	public String getSeriesDescription() {
		return getElementValue(Tags.SeriesDescription);
	}

	/**
	 * Convenience method to get the integer value of the Columns element.
	 * @return the integer value of the Columns element or -1 if the element does not exist.
	 */
	public int getColumns() {
		try { return dataset.getInteger(Tags.Columns).intValue(); }
		catch (Exception e) { return -1; }
	}

	/**
	 * Convenience method to get the integer value of the Rows element.
	 * @return the integer value of the Rows element or -1 if the element does not exist.
	 */
	public int getRows() {
		try { return dataset.getInteger(Tags.Rows).intValue(); }
		catch (Exception e) { return -1; }
	}

	/**
	 * Convenience method to get the integer value of the BitsStored element.
	 * @return the integer value of the Columns element or 12 if the element does not exist.
	 */
	public int getBitsStored() {
		try { return dataset.getInteger(Tags.BitsStored).intValue(); }
		catch (Exception e) { return 12; }
	}

	/**
	 * Convenience method to get the integer value of the NumberOfFrames element.
	 * @return the integer value of the NumberOfFrames element or 0 if the
	 * value is not available.
	 */
	public int getNumberOfFrames() {
		try { return dataset.getInteger(Tags.NumberOfFrames).intValue(); }
		catch (Exception e) { return 0; }
	}

	/**
	 * Convenience method to get the value of the PhotometricInterpretation element.
	 * @return the value of the PhotometricInterpretation element or the empty string
	 * if the element does not exist..
	 */
	public String getPhotometricInterpretation() {
		return getElementValue(Tags.PhotometricInterpretation);
	}

	/**
	 * Convenience method to get the integer value of the SamplesPerPixel element.
	 * @return the integer value of the SamplesPerPixel element or 1 if the
	 * value is not available.
	 */
	public int getSamplesPerPixel() {
		try { return dataset.getInteger(Tags.SamplesPerPixel).intValue(); }
		catch (Exception e) { return 1; }
	}

	/**
	 * Convenience method to get the integer value of the PlanarConfiguration element.
	 * @return the integer value of the PlanarConfiguration element or 1 if the
	 * value is not available.
	 */
	public int getPlanarConfiguration() {
		try { return dataset.getInteger(Tags.PlanarConfiguration).intValue(); }
		catch (Exception e) { return 1; }
	}

	/**
	 * Tests whether the DicomObject contains an image.
	 * The test is done by verifying that the PixelData element is present.
	 * @return true if the object contains an image; false otherwise.
	 */
	public boolean isImage() {
		return isImage;
	}

	/**
	 * Tests whether the DicomObject contains an image with encapsulated pixel data.
	 * @return true if the object contains an image with encapsulated pixel data; false otherwise.
	 */
	public boolean isEncapsulated() {
		return (isImage && fileParam.encapsulated);
	}

	/**
	 * Tests whether the DicomObject corresponds to an SR.
	 * The test is done by comparing the SOPClassUID to known SR SOPClassUIDs.
	 * @return true if the object corresponds to an SR; false otherwise.
	 */
	public boolean isSR() {
		return SopClass.isSR(getSOPClassUID());
	}

	/**
	 * Tests whether the DicomObject corresponds to a KIN.
	 * The test is done by comparing the SOPClassUID to the KIN SOPClassUID.
	 * @return true if the object corresponds to a KIN; false otherwise.
	 */
	public boolean isKIN() {
		return SopClass.isKIN(getSOPClassUID());
	}

	/**
	 * Tests whether the DicomObject corresponds to a DICOMDIR.
	 * The test is done by comparing the MediaStorageSOPClassUID to the DICOMDIR SOPClassUID.
	 * @return true if the object corresponds to a DICOMDIR; false otherwise.
	 */
	public boolean isDICOMDIR() {
		return SopClass.isDICOMDIR(getMediaStorageSOPClassUID());
	}

	/**
	 * Tests whether the DicomObject corresponds to a KIN that is an IHE TCE manifest.
	 * @return true if the object corresponds to a TCE Manifest; false otherwise.
	 */
	public boolean isManifest() {
		return isManifest;
	}

	//Check whether this object is a TCE Manifest
	private boolean checkManifest() {
		if (!isKIN()) return false;
		try {
			DcmElement cncsElement = dataset.get(Tags.ConceptNameCodeSeq);
			Dataset sq = cncsElement.getItem(0);
			String codeValue = sq.getString(Tags.CodeValue).trim();
			if (codeValue.equals("TCE001")) return true;
			if (codeValue.equals("TCE002")) return true;
			if (codeValue.equals("TCE007")) return true;
			return false;
		}
		catch (Exception e) { return false; }
	}

	/**
	 * Tests whether the DicomObject is an IHE TCE ATFI SR document.
	 * @return true if the object corresponds to a TCE; false otherwise.
	 */
	public boolean isAdditionalTFInfo() {
		return isAdditionalTFInfo;
	}

	//Check whether this object is a TCE ATFI Object
	private boolean checkAdditionalTFInfo() {
		if (!isSR()) return false;
		try {
			DcmElement cncsElement = dataset.get(Tags.ConceptNameCodeSeq);
			Dataset sq = cncsElement.getItem(0);
			String codeValue = sq.getString(Tags.CodeValue).trim();
			if (codeValue.equals("TCE006")) return true;
			return false;
		}
		catch (Exception e) { return false; }
	}

	/**
	 * Get the IHE TCE additional teaching file info in a Hashtable.
	 * @return the additional teaching file info Hashtable, or null if
	 * this object is not an IHE Additional Teaching File Info object.
	 */
	public Hashtable getAdditionalTFInfo() {
		if (!isAdditionalTFInfo()) return null;
		return new ATFI(dataset,charset);
	}

	/**
	 * Gets a brief description of this DicomObject.
	 * @return "TCE Manifest" or SOP Class Name.
	 */
	public String getDescription() {
		if (isManifest()) return "TCE Manifest";
		return getSOPClassName();
	}

	/**
	 * Get an array of SOPInstanceUIDs for all the instances listed in the
	 * Current Requested Procedure Evidence Sequence element in an IHR TCE manifest.
	 * @return the array if this object is a manifest; null otherwise.
	 */
	public String[] getInstanceList() {
		return getList(Tags.CurrentRequestedProcedureEvidenceSeq, Tags.RefSOPInstanceUID);
	}

	/**
	 * Get an array of PersonNames from the Content Sequence element in an IHE TCE manifest
	 * @return the array if this object is a manifest; null otherwise.
	 */
	public String[] getObserverList() {
		return getList(Tags.ContentSeq, Tags.PersonName);
	}

	/**
	 * Get the text of the Key Object Description from the Content Sequence of
	 * an IHE TCE Manifest.
	 * @return the Key Object Description text if this object is a manifest; null otherwise.
	 */
	public String getKeyObjectDescription() {
		if (!isManifest()) return null;
		DcmElement cs = dataset.get(Tags.ContentSeq);
		Dataset csItem;
		int i = 0;
		while ((csItem = cs.getItem(i)) != null) {
			i++;
			DcmElement cncs = csItem.get(Tags.ConceptNameCodeSeq);
			if (cncs != null) {
				Dataset cncsItem = cncs.getItem(0);
				if (cncsItem != null) {
					DcmElement cv = cncsItem.get(Tags.CodeValue);
					try {
						if ((cv != null) && cv.getString(charset).equals("113012")) {
							DcmElement tv = csItem.get(Tags.TextValue);
							if (tv != null) return tv.getString(charset);
						}
					}
					catch (Exception keepLooking) {
						logger.debug("Encountered data problem looking for CodeValue=\"113012\", continuing to search.");
					}
				}
			}
		}
		return null;
	}

	/**
	 * Get a Hashtable containing entries obtained by parsing the supplied
	 * text. The hashtable contains entries for names designated in the text
	 * by "mirc:name=" at the beginning of a line.
	 * All the text following the equal sign and before the next name designation
	 * is assigned to the key. Any name can appear only once (or the last value
	 * is stored in the table).
	 * @param kodText the text to parse.
	 * @return the parsed text, or null if the supplied string is null.
	 */
	public Hashtable getParsedKOD(String kodText) {
		Hashtable<String,String> kodTable = new Hashtable<String,String>();
		if (kodText != null) {
			Pattern pattern = Pattern.compile("mirc:[^\\s]+=");
			Matcher matcher = pattern.matcher(kodText);
			String name = "";
			int lastEnd = 0;
			while (matcher.find()) {
				if (!name.equals("")) kodTable.put(name,kodText.substring(lastEnd,matcher.start()));
				lastEnd = matcher.end();
				name = matcher.group();
				name = name.substring(5, name.length()-1);
			}
			if (!name.equals("")) kodTable.put(name,kodText.substring(lastEnd));
		}
		return kodTable;
	}

	//Get a list of element values by walking the tree below a starting
	//element in the dataset, and finding all instances of a specific tag.
	private String[] getList(int startingTag, int tagToFind) {
		if (!isManifest()) return null;
		try {
			ArrayList<String> list = new ArrayList<String>();
			DcmElement el = dataset.get(startingTag);
			getList(list, el, tagToFind);
			String[] strings = new String[list.size()];
			strings = list.toArray(strings);
			return strings;
		}
		catch (Exception ex) { return null; }
	}

	//Walk a tree of elements to find any that match a tag
	//and add their values to a list.
	private void getList(ArrayList<String> list, DcmElement el, int tag) {
		//If this is the element we want, then get its value
		try {
			if (el.vr() != VRs.SQ) {
				//It's not a sequence; see if it's a tag match.
				if (el.tag() == tag) {
					list.add(el.getString(charset));
				}
				return;
			}
			else {
				//It's a sequence; walk the item tree looking for matches.
				int i = 0;
				Dataset ds;
				while ((ds=el.getItem(i++)) != null) {
					for (Iterator it=ds.iterator(); it.hasNext(); ) {
						DcmElement e = (DcmElement)it.next();
						getList(list,e,tag);
					}
				}
			}
		}
		catch (Exception ex) { return; }
	}

	/**
	 * Get an HTML page listing all the elements in the DICOM object and
	 * their values.
	 * @return the HTML page.
	 * @throws Exception if the process fails.
	 */
	public String getElementTablePage(boolean decipherLinks) {
		StringBuffer sb = new StringBuffer();
		sb.append("<html>\n");
		sb.append("<head>\n");
		sb.append("<title>"+file.getName()+"</title>\n");
		sb.append("<link rel=\"Stylesheet\" type=\"text/css\" media=\"all\" href=\"/DicomListing.css\"></link>");
		if (decipherLinks) {
			String filepath = file.getAbsolutePath();
			filepath = filepath.replaceAll("\\\\","/");
			sb.append("<script language=\"JavaScript\" type=\"text/javascript\" src=\"/JSAJAX.js\">;</script>\n");
			sb.append("<script language=\"JavaScript\" type=\"text/javascript\" src=\"/JSDecipher.js\">;</script>\n");
			sb.append("<script>\n");
			sb.append("  var filepath = \""+filepath+"\";\n");
			sb.append("</script>\n");
		}
		sb.append("</head>\n");
		sb.append("<body>\n");
		sb.append("<center>\n");
		sb.append(getElementTable(decipherLinks));
		sb.append("</center>\n");
		sb.append("</body>\n");
		sb.append("</html>\n");
		return sb.toString();
	}

	/**
	 * Get a String containing an HTML table element listing all the
	 * elements in the DICOM object and their values. This method is included
	 * for backward compatibility; it does NOT include links to decipher
	 * private elements.
	 * @return the HTML text listing all the elements in the DICOM object.
	 * @throws Exception if the process fails.
	 */
	public String getElementTable() {
		return getElementTable(false);
	}

	/**
	 * Get a String containing an HTML table element listing all the
	 * elements in the DICOM object and their values.
	 * @param decipherLinks include links to decipher encrypted private elements.
	 * @return the HTML text listing all the elements in the DICOM object.
	 * @throws Exception if the process fails.
	 */
	public String getElementTable(boolean decipherLinks) {
		SpecificCharacterSet cs = dataset.getSpecificCharacterSet();
		StringBuffer table = new StringBuffer();
		table.append("<h3>"+file.getName());
		table.append("<br>"+getTransferSyntaxName());
		table.append("<br>"+getSOPClassName());
		table.append("</h3>\n");
		table.append("<table>\n");
		table.append("<tr>" +
						"<th>Element</th>" +
						"<th style=\"text-align:left;\">Name</th>" +
						"<th>VR</th>" +
						"<th>VM</th>" +
						"<th>Length</th>" +
						"<th style=\"text-align:left;\">Data</th>" +
					"</tr>\n");
		walkDataset(dataset.getFileMetaInfo(), cs, table, "", false);
		walkDataset(dataset, cs, table, "", decipherLinks);
		table.append("</table>\n");
		return table.toString();
	}

	private void walkDataset(
						DcmObject dataset,
						SpecificCharacterSet cs,
						StringBuffer table,
						String prefix,
						boolean decipherLinks) {
		int maxLength = 70;
		DcmElement el;
		String tagString;
		String tagName;
		String vrString;
		String valueString;
		String valueLength;
		int vr;
		int vm;
		if (dataset == null) return;
		for (Iterator it=dataset.iterator(); it.hasNext(); ) {
			table.append("<tr>");
			el = (DcmElement)it.next();
			int tag = el.tag();
			tagString = checkForNull(Tags.toString(tag));

			if ((tag & 0x10000) != 0) tagName = "";
			else {
				try { tagName = checkForNull(tagDictionary.lookup(tag).name); }
				catch (Exception e) { tagName = ""; }
			}

			vr = el.vr();
			vrString = VRs.toString(vr);
			if (vrString.equals("")) vrString = "["+Integer.toHexString(vr)+"]";

			vm = el.vm(cs);

			//Set up the call if this is an encrypted element.
			//The requirements are that the element be in a private group
			//whose owner is "CTP" and that the length of the element be
			//a multiple of 4 (because it must be a Base64 string).
			boolean decipher = false;
			boolean ctp = false;
			if (((tag & 0x10000) != 0) && ((tag & 0x0000ff00) != 0)) {
				int blk = (tag & 0xffff0000) | ((tag & 0x0000ff00) >> 8);
				if (ctp = getElementValue(blk).equals("CTP")) {
					String v = new String(dataset.getByteBuffer(tag).array());
					if ((v.length() % 4) == 0) decipher = decipherLinks;
				}
			}

			if (decipher) table.append("<td onclick=\"decipher();\">");
			else table.append("<td>");
			table.append(prefix+tagString);
			table.append("</td>");

			table.append("<td>"+tagName+"</td>");
			table.append("<td align=center>"+vrString+"</td>");
			table.append("<td align=center>"+vm+"</td>");
			table.append("<td align=center>"+el.length()+"</td>");

			if (!vrString.toLowerCase().startsWith("sq")) {
				if (ctp) {
					valueString = getElementValue(el.tag());
					if (valueString == null)
						table.append("<td>"+nullValue+"</td>");
					else if (valueString.length() <= maxLength)
						table.append(
							"<td><span>"
							+ valueString.replaceAll("\\s","&nbsp;")
							+ "</span></td>");
					else {
						table.append(
							"<td><span>"
							+ valueString.substring(0,maxLength).replaceAll("\\s","&nbsp;")
							+ "</span>...</td>");
					}
				}
				else {
					valueString = getElementValueString(cs, el, maxLength);
					table.append("<td>" + valueString + "</td>");
				}
				table.append("</tr>\n");
			}
			else {
				table.append("</tr>\n");
				int i = 0;
				Dataset sq;
				while ((sq=el.getItem(i++)) != null) {
					walkDataset(sq,cs,table,prefix+i+">",false);
				}
			}
		}
	}

	//Make a displayable text value for an element, handling
	//cases where the element is multivalued and where the element value
	//is too long to be reasonably displayed.
	private String getElementValueString(SpecificCharacterSet cs, DcmElement el, int maxLength) {
		StringBuffer sb = new StringBuffer();
		int tag = el.tag();
		if ((tag & 0xffff0000) >= 0x60000000) return "...";
		String[] s;
		try { s = el.getStrings(cs); }
		catch (Exception e) { s = null; }
		if (s == null) return nullValue;
		else {
			for (int i=0; i<s.length; i++) {
				if (s[i].length() <= maxLength) {
					sb.append("<span>" + s[i].replaceAll("\\s","&nbsp;") + "</span>");
				}
				else {
					sb.append(
						"<span>" + s[i].substring(0,maxLength).replaceAll("\\s","&nbsp;") + "</span>...");
				}
				if (i < s.length - 1) sb.append("<br>");
			}
		}
		return sb.toString();
	}

	//Handle null element values (e.g. missing elements).
	private String checkForNull(String s) {
		if (s != null) return s;
		return nullValue;
	}

	//An HTML string indicating a null value (in red).
	String nullValue = "<font color=red>null</font>";

	//A class to encapsulate the information in a
	//TCE Additional Teaching File Info object.
	class ATFI extends Hashtable<String,String> {
		public ATFI(Dataset dataset, SpecificCharacterSet charset) {
			super();
			Hashtable<String,String> codes = getCodes();
			try {
				DcmElement csElement = dataset.get(Tags.ContentSeq);
				int i = 0;
				Dataset ds;
				while ((ds = csElement.getItem(i++)) != null) {
					putItem(ds,charset,codes);
				}
			}
			catch (Exception unable) { logger.debug("Unable to populate the ATFI Hashtable", unable); }
		}
		private void putItem(Dataset ds,
							 SpecificCharacterSet charset,
							 Hashtable<String,String> codes) {
			try {
				//Make sure this is a container
				DcmElement rt = ds.get(Tags.RelationshipType);
				DcmElement vt = ds.get(Tags.ValueType);
				if ((rt == null) || (vt == null) ||
					(!rt.getString(charset).equals("CONTAINS"))) return;
				//Get the code of the container type
				DcmElement cncsElement = ds.get(Tags.ConceptNameCodeSeq);
				Dataset item = cncsElement.getItem(0);
				DcmElement cvElement = item.get(Tags.CodeValue);
				String cvString = cvElement.getString(charset);
				//Get the value
				String valueString = null;
				if (vt.getString(charset).equals("TEXT")) {
					//Handle text values here
					DcmElement tvElement = ds.get(Tags.TextValue);
					valueString = tvElement.getString(charset);
				}
				else if (vt.getString(charset).equals("CODE")) {
					//Handle code values here
					DcmElement ccsElement = ds.get(Tags.ConceptCodeSeq);
					Dataset ccsItem = ccsElement.getItem(0);
					DcmElement ccscvElement = ccsItem.get(Tags.CodeValue);
					String codeString = ccscvElement.getString(charset);
					valueString = codes.get(codeString);
					//If the code is not in the table, then use the
					//code itself. This is necessary to handle all the
					//modalities without having to put them in the table.
					if (valueString == null) valueString = codeString;
				}
				if (valueString != null) {
					String name = codes.get(cvString);
					if (name != null) {
						String current = this.get(name);
						if (current != null) valueString = current + "; " + valueString;
						this.put(name,valueString);
					}
				}
			}
			catch (Exception skip) { return; };
		}
		private Hashtable<String,String> getCodes() {
			Hashtable<String,String> codes = new Hashtable<String,String>();
			codes.put("TCE101","author/name");
			codes.put("TCE102","author/affiliation");
			codes.put("TCE103","author/contact");
			codes.put("TCE104","abstract");
			codes.put("TCE105","keywords");
			codes.put("121060","history");
			codes.put("121071","findings");
			codes.put("TCE106","discussion");
			codes.put("111023","differential-diagnosis");
			codes.put("TCE107","diagnosis");
			codes.put("112005","anatomy");
			codes.put("111042","pathology");
			codes.put("TCE108","organ-system");
			codes.put("121139","modality");
			codes.put("TCE109","category");
			codes.put("TCE110","level");

			codes.put("TCE201","Primary");
			codes.put("TCE202","Intermediate");
			codes.put("TCE203","Advanced");

			codes.put("TCE301","Musculoskeletal;");
			codes.put("TCE302","Pulmonary");
			codes.put("TCE303","Cardiovascular");
			codes.put("TCE304","Gastrointestinal");
			codes.put("TCE305","Genitourinary");
			codes.put("TCE306","Neuro");
			codes.put("TCE307","Vascular and Interventional");
			codes.put("TCE308","Nuclear");
			codes.put("TCE309","Ultrasound");
			codes.put("TCE310","Pediatric");
			codes.put("TCE311","Breast");
			return codes;
		}
	}

	/**
	 * Evaluate a boolean script for this DicomObject. See the RSNA
	 * CTP wiki article (The CTP DicomFilter) for information on the
	 * script language.
	 * @param scriptFile the text file containing the expression to
	 * compute based on the values in this DicomObject.
	 * @return the computed boolean value of the script.
	 */
	public boolean matches(File scriptFile) {
		String script = FileUtil.getText(scriptFile);
		return matches(script);
	}

	/**
	 * Evaluate a boolean script for this DicomObject. See the RSNA
	 * CTP wiki article (The CTP DicomFilter) for information on the
	 * script language.
	 * @param script the expression to compute based on the values
	 * in this DicomObject.
	 * @return the computed boolean value of the script.
	 */
	public boolean matches(String script) {
		logger.debug("Match script:\n"+script);

		Tokenizer tokenizer = new Tokenizer(script);
		Stack<Operator> operators = new Stack<Operator>();
		Stack<Token> tokens = new Stack<Token>();
		operators.push(Operator.createSentinel());

		//Get the expression, evaluate it, and return the result.
		boolean result = false;
		try {
			expression(tokenizer, operators, tokens);
			tokenizer.expect(Token.END);
			result = unstack(tokens);
		}
		catch (Exception ex) { logger.error("", ex); }
		logger.debug("Match result = "+result);
		return result;
	}

	boolean unstack(Stack<Token> tokens) {
		if (tokens.size() == 0) return false;
		Token tok = tokens.pop();
		if (tok instanceof Operand) {
			return ((Operand)tok).getValue();
		}
		else {
			Operator op = (Operator)tok;
			boolean value = false;
			boolean v1, v2;
			if (op.c == '!')
				value = !unstack(tokens);
			else if (op.c == '+') {
				//note: you must unstack separately and then do the logic
				//or the optimizer may omit one unstack, leaving the stack
				//in a mess.
				v1 = unstack(tokens);
				v2 = unstack(tokens);
				value = v1 || v2;
			}
			else if (op.c == '*') {
				//see the note above.
				v1 = unstack(tokens);
				v2 = unstack(tokens);
				value = v1 && v2;
			}
			return value;
		}
	}

	void expression(Tokenizer t, Stack<Operator> ops, Stack<Token> toks) throws Exception {
		parse(t,ops,toks);
		while (t.next().isOperator() &&
				((Operator)t.next()).isBinary()) {
			pushOperator(t.next(),ops,toks);
			t.consume();
			parse(t,ops,toks);
		}
		while (!ops.peek().isSentinel()) {
			popOperator(ops,toks);
		}
	}

	void parse(Tokenizer t, Stack<Operator> ops, Stack<Token> toks) throws Exception {
		if (t.next().isOperand()) {
			toks.push(t.next());
			t.consume();
		}
		else if (t.next().isLP()) {
			t.consume();
			ops.push(Operator.createSentinel());
			expression(t,ops,toks);
			t.expect(Token.RP);
			ops.pop();
		}
		else if (t.next().isOperator() &&
				((Operator)t.next()).isUnary()) {
			pushOperator(t.next(),ops,toks);
			t.consume();
			parse(t,ops,toks);
		}
		else throw new Exception("Failure in parsing the script.");
	}

	void popOperator(Stack<Operator> ops, Stack<Token> toks) {
		toks.push(ops.pop());
	}

	void pushOperator(Token tok, Stack<Operator> ops, Stack<Token> toks) {
		Operator op = (Operator)tok;
		while (ops.peek().isHigherThan(op))
			popOperator(ops,toks);
		ops.push(op);
	}


	//The rest of the code is for parsing the script and evaluating the result.
	class Tokenizer {
		String script;
		int index;
		Token nextToken;

		public Tokenizer(String script) {
			this.script = script;
			index = 0;
			nextToken = getToken();
		}
		public void expect(int type) throws Exception {
			if (nextToken.equals(type))
				consume();
			else
				throw new Exception(
					"Error in script: "
					+Token.getTypeName(type)
					+" expected, but "
					+Token.getTypeName(nextToken.getType())
					+" found.");
		}
		public Token next() {
			return nextToken;
		}
		public Token consume() {
			Token temp = nextToken;
			nextToken = getToken();
			return temp;
		}
		Token getToken() {
			skipWhitespace();
			if (index >= script.length())
				return new End();
			char c = script.charAt(index);
			if ((c == '[') || Character.isLetter(c))
				return new Operand(this);
			else if (c == '(')
				return new LP(this);
			else if (c == ')')
				return new RP(this);
			else if (Operator.isOperator(c))
				return new Operator(this);
			return new Unknown();
		}
		void skipWhitespace() {
			boolean inComment = false;
			while (index < script.length()) {
				char c = script.charAt(index);
				if (inComment) {
					if (c == '\n') inComment = false;
					index++;
				}
				else if (c == '/') {
					int k = index + 1;
					if ((k < script.length()) && (script.charAt(k) == '/')) {
						inComment = true;
						index += 2;
					}
					else return;
				}
				else if (Character.isWhitespace(c)) index++;
				else return;
			}
		}
		public char getChar() {
			if (index < script.length())
				return script.charAt(index++);
			return 0;
		}
	}

	static class Operator extends Token {
		public char c;	//the operator character
		public int p;	//the precedence
		static String ops = "?+*!";
		static int[] prec = {0,1,2,3};

		public Operator(Tokenizer t) {
			super(OPERATOR);
			this.c = t.getChar();
			this.p = ops.indexOf(c);
			if (p != -1) p = prec[p];
		}
		public Operator(char c) {
			super(OPERATOR);
			this.c = c;
			this.p = ops.indexOf(c);
			if (p != -1) p = prec[p];
		}
		public static Operator createSentinel() {
			return new Operator('?');
		}
		public static boolean isOperator(char c) {
			int x = ops.indexOf(c);
			return (x > 0);
		}
		public boolean isOperator() {
			return (p != -1);
		}
		public boolean isSentinel() {
			return (c == '?');
		}
		public boolean isUnary() {
			return (c == '!');
		}
		public boolean isBinary() {
			return (c == '+') || (c == '*');
		}
		public boolean isHigherThan(Operator q) {
			return (p >= q.p);
		}
		public boolean isLowerThan(Operator q) {
			return (p < q.p);
		}
		public String toString() {
			return "" + c;
		}
	}

	class Operand extends Token {
		public boolean value = false;
		public Operand(Tokenizer t) {
			super(OPERAND);
			String identifier = getField(t,'.').trim();
			if (identifier.equals("true"))
				value = true;
			else if (identifier.equals("false"))
				value = false;
			else {
				String method = getField(t,'(').trim();
				String match = getField(t,')').trim();
				if ((match.length() > 1) &&
						(match.charAt(0) == '"') &&
							(match.charAt(match.length()-1) == '"')) {

					//Update the properties for the value of the identifier.
					String element = getElementValue(identifier);

					String elementLC = element.toLowerCase();
					match = match.substring(1, match.length()-1);
					String matchLC = match.toLowerCase();

					if (method.equals("equals"))
						value = element.equals(match);

					else if (method.equals("equalsIgnoreCase"))
						value = element.equalsIgnoreCase(match);

					else if (method.equals("matches"))
						value = element.matches(match);

					else if (method.equals("contains"))
						value = (element.contains(match));

					else if (method.equals("containsIgnoreCase"))
						value = (elementLC.contains(matchLC));

					else if (method.equals("startsWith"))
						value = element.startsWith(match);

					else if (method.equals("startsWithIgnoreCase"))
						value = elementLC.startsWith(matchLC);

					else if (method.equals("endsWith"))
						value = element.endsWith(match);

					else if (method.equals("endsWithIgnoreCase"))
						value = elementLC.endsWith(matchLC);

					else {
						logger.error("Unknown function: "+identifier+"."+method+"(\""+match+"\")");
						value = false;
					}
					logger.debug(identifier+"="+element+" -> { "+identifier+"."+method+"(\""+match+"\")="+value+" }");
				}
			}
		}
		String getField(Tokenizer t, char delim) {
			String f = "";
			char c;
			boolean inQuote = false;
			while ((c = t.getChar()) != 0) {
				if (c == '"') inQuote = !inQuote;
				if (!inQuote && (c == delim)) break;
				f += c;
			}
			return f;
		}
		public boolean getValue() {
			return value;
		}
		public String toString() {
			return value ? "true" : "false";
		}
	}

	class LP extends Token {
		public LP(Tokenizer t) {
			super(LP);
			t.getChar();
		}
	}

	class RP extends Token {
		public RP(Tokenizer t) {
			super(RP);
			t.getChar();
		}
	}

	class End extends Token {
		public End() {
			super(END);
		}
	}

	class Unknown extends Token {
		public Unknown() {
			super(UNKNOWN);
		}
	}

	static class Token {
		static int OPERATOR = 0;
		static int OPERAND = 1;
		static int LP = 2;
		static int RP = 3;
		static int END = -1;
		static int UNKNOWN = -2;
		int type;
		public Token(int type) {
			this.type = type;
		}
		public boolean equals(int type) {
			return (this.type == type);
		}
		public boolean isOperator() {
			return (type == OPERATOR);
		}
		public boolean isOperand() {
			return (type == OPERAND);
		}
		public boolean isLP() {
			return (type == LP);
		}
		public boolean isRP() {
			return (type == RP);
		}
		public boolean isEND() {
			return (type == END);
		}
		public int getType() {
			return type;
		}
		public String getTypeName() {
			return getTypeName(this.type);
		}
		public static String getTypeName(int type) {
			if (type == OPERATOR) return "OPERATOR";
			else if (type == OPERAND) return "OPERAND";
			else if (type == LP) return "LP";
			else if (type == RP) return "RP";
			else if (type == END) return "END";
			else return "UNKNOWN";
		}
		public String toString() {
			return getTypeName();
		}
	}
	//**********************************
	// End of the code for the matcher
	//**********************************


}
