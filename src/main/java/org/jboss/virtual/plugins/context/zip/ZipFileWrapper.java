/*
* JBoss, Home of Professional Open Source
* Copyright 2006, JBoss Inc., and individual contributors as indicated
* by the @authors tag. See the copyright.txt in the distribution for a
* full listing of individual contributors.
*
* This is free software; you can redistribute it and/or modify it
* under the terms of the GNU Lesser General Public License as
* published by the Free Software Foundation; either version 2.1 of
* the License, or (at your option) any later version.
*
* This software is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
* Lesser General Public License for more details.
*
* You should have received a copy of the GNU Lesser General Public
* License along with this software; if not, write to the Free
* Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
* 02110-1301 USA, or see the FSF site: http://www.fsf.org.
*/
package org.jboss.virtual.plugins.context.zip;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Enumeration;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.jboss.logging.Logger;
import org.jboss.virtual.VFSUtils;

/**
 * ZipFileWrapper - for abstracted access to zip files on disk
 *
 * It releases and reacquires the underlying ZipFile as necessary
 *
 * @author <a href="strukelj@parsek.net">Marko Strukelj</a>
 * @version $Revision: 1.0 $
 */
class ZipFileWrapper extends ZipWrapper
{
   /** Logger */
   private static final Logger log = Logger.getLogger(ZipFileWrapper.class);

   /** Is forceNoReaper enabled */
   private static boolean forceNoReaper;

   static
   {
      forceNoReaper = AccessController.doPrivileged(new CheckNoReaper());

      if (forceNoReaper)
         log.info("VFS forced no-reaper-mode is enabled.");
   }

   /** Underlying zip archive file */
   private File file;

   /** Zip inflater wrapped around file */
   private ZipFile zipFile;

   /** autoClean flag - true for extracted nested jars that we want removed when this wrapper is closed */
   private boolean autoClean;

   /** true if noReaper mode is forced on a per-instance basis */
   private boolean noReaperOverride;

   /** ZipEntrycontext that owns us */
   private ZipEntryContext ctx;

   /**
    * ZipFileWrapper
    *
    * @param archive file to the archive
    * @param autoClean  should archive be deleted after use
    * @param noReaperOverride flag to specify if reaper be used or not
    */
   ZipFileWrapper(ZipEntryContext ctx, File archive, boolean autoClean, boolean noReaperOverride)
   {
      this.ctx = ctx;
      this.noReaperOverride = noReaperOverride;
      init(archive, autoClean);
   }

   /**
    * ZipFileWrapper
    *
    * @param rootPathURI URI to the archive - will be passed to File constructor as-is
    * @param autoClean  should archive be deleted after use
    * @param noReaperOverride flag to specify if reaper be used or not
    */
   ZipFileWrapper(URI rootPathURI, boolean autoClean, boolean noReaperOverride)
   {
      this.noReaperOverride = noReaperOverride;
      File rootFile = new File(rootPathURI);
      if(rootFile.isFile() == false)
         throw new RuntimeException("File not found: " + rootFile);

      init(rootFile, autoClean);
   }

   /**
    * Extra initialization in addition to what's in constructors
    *
    * @param archive the archive file
    * @param autoClean auto clean flag
    */
   private void init(File archive, boolean autoClean)
   {
      file = archive;
      lastModified = file.lastModified();
      this.autoClean = autoClean;
      if (autoClean)
         file.deleteOnExit();
   }

   /**
    * Check if archive exists
    *
    * @return true if file exists on disk
    */
   boolean exists()
   {
      return file.isFile();
   }

   /**
    * Get lastModified for the archive
    *
    * @return lastModified timestamp of the file on disk
    */
   long getLastModified()
   {
      return file.lastModified();
   }

   /**
    * Get the name of the archive
    *
    * @return name of the file on disk
    */
   String getName()
   {
      return file.getName();
   }

   /**
    * Get the size of the archive
    *
    * @return size of the file on disk
    */
   long getSize()
   {
      return file.length();
   }

   /**
    * Open a <tt>ZipFile</tt> if none currently exists. If reaper mode is active, apply for monitoring.
    *
    * @return a ZipFile
    * @throws IOException for any error
    */
   private ZipFile ensureZipFile() throws IOException
   {
      if (zipFile == null)
      {
         zipFile = new JarFile(file);
         if (forceNoReaper == false && noReaperOverride == false)
            ZipFileLockReaper.getInstance().register(this);
      }

      return zipFile;
   }

   /**
    * Close a <tt>ZipFile</tt> if currently open. If reaper mode is active, unregister from monitoring
    *
    * @throws IOException for any error
    */
   synchronized void closeZipFile() throws IOException
   {
      if (zipFile != null && getReferenceCount() <= 0)
      {
         ZipFile zf = zipFile;
         zipFile = null;
         zf.close();
         if (forceNoReaper == false && noReaperOverride == false)
         {
            ZipFileLockReaper.getInstance().unregister(this);

            // reset owner context
            if (ctx != null)
            {
               ctx.resetInitStatus();
            }
         }
      }
   }

   /**
    * Get the contents of the given <tt>ZipEntry</tt> as stream
    *
    * @param info a zip entry info
    * @return an InputStream that locks the file for as long as it's open
    * @throws IOException for any error
    */
   synchronized InputStream openStream(ZipEntryInfo info) throws IOException
   {
      // JBVFS-57 JarInputStream composition
      if (info.isDirectory())
         return recomposeZipAsInputStream(info.getName());

      ensureZipFile();

      // make sure input stream and certs reader work on the same update
      ZipEntry update = info.getEntry();
      if (info.requiresUpdate())
      {
         // we need to update entry, from the new zif file
         update = zipFile.getEntry(info.getName());
         info.setEntry(update);
      }

      InputStream is = zipFile.getInputStream(update);
      if (is == null)
         throw new IOException("Entry no longer available: " + info.getName() + " in file " + file);

      InputStream zis = new CertificateReaderInputStream(info, this, is);

      incrementRef();
      return zis;
   }

   /**
    * Get raw bytes of this archive in its compressed form
    *
    * @return an InputStream
    * @throws FileNotFoundException if archive doesn't exist
    */
   InputStream getRootAsStream() throws FileNotFoundException
   {
      return new FileInputStream(file);
   }

   /**
    * Increment usage count by one and ensure <tt>ZipFile</tt> is open.
    *
    * @throws IOException
    */
   synchronized void acquire() throws IOException
   {
      ensureZipFile();
      incrementRef();
   }

   /**
    * Decrement usage count by one
    */
   synchronized void release() {
      super.release();
      if (forceNoReaper || noReaperOverride)
         try
         {
            closeZipFile();
         }
         catch(Exception ex)
         {
            log.warn("Failed to release file: " + file);
         }
   }

   /**
    * Enumerate contents of zip archive
    */
   synchronized Enumeration<? extends ZipEntry> entries() throws IOException
   {
      return ensureZipFile().entries();
   }

   /**
    * Close the archive, perform autoclean if requested
    */
   void close()
   {
      try
      {
         closeZipFile();
      }
      catch(Exception ignored)
      {
         log.warn("IGNORING: Failed to release file: " + file, ignored);
      }

      if (autoClean)
         traceDelete(file);
   }

   /**
    * Delete file.
    *
    * @param wrapper the wrapper
    * @throws IOException for any error
    */
   void deleteFile(ZipFileWrapper wrapper) throws IOException
   {
      if (file.equals(wrapper.file))
      {
         closeZipFile();
         traceDelete(file);
      }
   }

   /**
    * Trace file deletion.
    *
    * @param ref the file ref
    */
   private static void traceDelete(File ref)
   {
      if (ref.delete() == false && log.isTraceEnabled())
      {
         log.trace("Failed to delete file: " + ref);
      }
   }

   /**
    * Delete the archive
    *
    * @param gracePeriod max time to wait for any locks
    * @return true if file was deleted, false otherwise
    * @throws IOException if an error occurs
    */
   boolean delete(int gracePeriod) throws IOException
   {
      boolean exists = file.isFile();
      if (exists == false)
         return false;

      boolean interrupted = Thread.interrupted();
      try
      {
         long endOfGrace = System.currentTimeMillis() + gracePeriod;
         do
         {
            closeZipFile();
            ZipFileLockReaper.getInstance().deleteFile(this);
            try
            {
               if (file.exists() && file.delete() == false)
                  Thread.sleep(100);
               else
                  return true;
            }
            catch (InterruptedException e)
            {
               interrupted = true;
               return file.exists() == false || file.delete();
            }
         }
         while(System.currentTimeMillis() < endOfGrace);
      }
      finally
      {
         if (interrupted)
            Thread.currentThread().interrupt();
      }

      return file.delete();
   }

   protected synchronized void recomposeZip(OutputStream baos, String path) throws IOException
   {
      ZipOutputStream zout = new ZipOutputStream(baos);
      zout.setMethod(ZipOutputStream.STORED);

      ensureZipFile();
      Enumeration<? extends ZipEntry> entries = zipFile.entries();
      while(entries.hasMoreElements())
      {
         ZipEntry oldEntry = entries.nextElement();
         if (oldEntry.getName().startsWith(path))
         {
            String newName = oldEntry.getName().substring(path.length());
            if(newName.length() == 0)
               continue;

            ZipEntry newEntry = new ZipEntry(newName);
            newEntry.setComment(oldEntry.getComment());
            newEntry.setTime(oldEntry.getTime());
            newEntry.setSize(oldEntry.getSize());
            newEntry.setCrc(oldEntry.getCrc());
            zout.putNextEntry(newEntry);
            if (oldEntry.isDirectory() == false)
               VFSUtils.copyStream(zipFile.getInputStream(oldEntry), zout);
         }
      }
      zout.close();
   }

   /**
    * toString
    *
    * @return String description of this archive
    */
   public String toString()
   {
      return super.toString() + " - " + file.getAbsolutePath();
   }

   /**
    * PriviligedAction used to read a system property
    */
   private static class CheckNoReaper implements PrivilegedAction<Boolean>
   {
      public Boolean run()
      {
         String forceString = System.getProperty(VFSUtils.FORCE_NO_REAPER_KEY, "false");
         return Boolean.valueOf(forceString);
      }
   }
}
