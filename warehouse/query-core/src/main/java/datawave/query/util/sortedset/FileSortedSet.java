package datawave.query.util.sortedset;

import datawave.webservice.query.exception.DatawaveErrorCode;
import datawave.webservice.query.exception.QueryException;
import org.apache.log4j.Logger;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * A sorted set that can be persisted into a file and still be read in its persisted state. The set can always be re-loaded and then all operations will work as
 * expected. This will support null contained in the underlying sets iff a comparator is supplied that can handle null values.
 *
 * The persisted file will contain the serialized entries, followed by the actual size.
 *
 * @param <E>
 */
public abstract class FileSortedSet<E> implements SortedSet<E>, Cloneable {
    private static Logger log = Logger.getLogger(FileSortedSet.class);
    protected boolean persisted = false;
    protected SortedSet<E> set = null;
    // A null entry placeholder
    public static final NullObject NULL_OBJECT = new NullObject();
    
    // The file handler that handles the underlying io
    public SortedSetFileHandler handler;
    
    /**
     * A factory that will provide the input stream and output stream to the same underlying file.
     * 
     * 
     * 
     */
    public interface SortedSetFileHandler {
        InputStream getInputStream() throws IOException;
        
        OutputStream getOutputStream() throws IOException;
        
        long getSize();
        
        void deleteFile();
    }
    
    /**
     * A class that represents a null object within the set
     * 
     * 
     * 
     */
    public static class NullObject implements Serializable {
        private static final long serialVersionUID = -5528112099317370355L;
        
    }
    
    /**
     * Create a file sorted set from another one
     * 
     * @param other
     */
    public FileSortedSet(FileSortedSet<E> other) {
        this.handler = other.handler;
        this.set = new TreeSet<>(other.set);
        this.persisted = other.persisted;
    }
    
    /**
     * Create a persisted sorted set
     * 
     * @param handler
     * @param persisted
     */
    public FileSortedSet(SortedSetFileHandler handler, boolean persisted) {
        this.handler = handler;
        this.set = new TreeSet<>();
        this.persisted = persisted;
    }
    
    /**
     * Create a persistede sorted set
     * 
     * @param comparator
     * @param handler
     * @param persisted
     */
    public FileSortedSet(Comparator<? super E> comparator, SortedSetFileHandler handler, boolean persisted) {
        this.handler = handler;
        this.set = new TreeSet<>(comparator);
        this.persisted = persisted;
    }
    
    /**
     * Create an unpersisted sorted set (still in memory)
     * 
     * @param set
     * @param handler
     */
    public FileSortedSet(SortedSet<E> set, SortedSetFileHandler handler) {
        this.handler = handler;
        this.set = new TreeSet<>(set);
        this.persisted = false;
    }
    
    /**
     * Create an sorted set out of another sorted set. If persist is true, then the set will be directly persisted using the set's iterator which avoid pulling
     * all of its entries into memory at once.
     *
     * @param set
     * @param handler
     */
    public FileSortedSet(SortedSet<E> set, SortedSetFileHandler handler, boolean persist) throws IOException {
        this.handler = handler;
        if (!persist) {
            this.set = new TreeSet<>(set);
            this.persisted = false;
        } else {
            this.set = new TreeSet<>(set.comparator());
            persist(set);
            persisted = true;
        }
    }
    
    /**
     * This will dump the set to the file, making the set "persisted"
     * 
     * @throws IOException
     */
    public void persist() throws IOException {
        if (!persisted) {
            persist(this.set);
            this.set.clear();
            persisted = true;
        }
    }
    
    /**
     * Persist the supplied set to a file as defined by this classes sorted set file handler.
     */
    private void persist(SortedSet<E> set) throws IOException {
        boolean verified = false;
        Exception failure = null;
        if (log.isDebugEnabled()) {
            log.debug("Persisting " + handler);
        }
        long start = System.currentTimeMillis();
        for (int i = 0; i < 10 && !verified; i++) {
            try {
                int actualSize = 0;
                List<E> firstOneHundred = new ArrayList<>();
                OutputStream stream = getOutputStream();
                try {
                    for (E t : set) {
                        writeObject(stream, t);
                        if (firstOneHundred.size() < 100) {
                            firstOneHundred.add(t);
                        }
                        actualSize++;
                    }
                    stream.write((actualSize >>> 24) & 0xFF);
                    stream.write((actualSize >>> 16) & 0xFF);
                    stream.write((actualSize >>> 8) & 0xFF);
                    stream.write((actualSize >>> 0) & 0xFF);
                } finally {
                    stream.close();
                }
                // verify we wrote at least the size....
                if (handler.getSize() < 4) {
                    throw new IOException("Failed to verify file existence");
                }
                // now verify the first 100 objects were written correctly
                InputStream inStream = getInputStream();
                try {
                    int count = 0;
                    for (E t : firstOneHundred) {
                        count++;
                        E input = readObject(inStream);
                        if (!equals(t, input)) {
                            throw new IOException("Failed to verify element " + count + " was written");
                        }
                    }
                } finally {
                    inStream.close();
                }
                
                // now verify the size was written at the end
                int test = readSize();
                if (test != actualSize) {
                    throw new IOException("Failed to verify file size was written");
                }
                
                verified = true;
                if (log.isDebugEnabled()) {
                    long delta = System.currentTimeMillis() - start;
                    log.debug("Persisting " + handler + " took " + delta + "ms");
                }
            } catch (Exception e) {
                log.warn("Attempt #" + i + " failed to persist " + handler);
                // ok, try again
                failure = e;
            }
        }
        if (!verified) {
            throw new IOException("Failed to write sorted set", failure);
        }
    }
    
    /**
     * Read the size from the file which is in the last 4 bytes.
     * 
     * @return the size (in terms of objects)
     * @throws IOException
     */
    private int readSize() throws IOException {
        long bytesToSkip = handler.getSize() - 4;
        InputStream inStream = handler.getInputStream();
        try {
            long total = 0;
            long cur = 0;
            
            while ((total < bytesToSkip) && ((cur = inStream.skip(bytesToSkip - total)) > 0)) {
                total += cur;
            }
            
            byte[] buffer = new byte[4];
            inStream.read(buffer);
            
            return ((buffer[3] & 0xFF)) + ((buffer[2] & 0xFF) << 8) + ((buffer[1] & 0xFF) << 16) + ((buffer[0]) << 24);
            
        } finally {
            inStream.close();
        }
    }
    
    /**
     * This will read the file into an in-memory set, making this file "unpersisted"
     * 
     * @throws IOException
     * @throws ClassNotFoundException
     */
    public void load() throws IOException, ClassNotFoundException {
        if (persisted) {
            try {
                int size = readSize();
                InputStream stream = getInputStream();
                try {
                    for (int i = 0; i < size; i++) {
                        E obj = readObject(stream);
                        set.add(obj);
                    }
                } finally {
                    stream.close();
                }
            } catch (Exception e) {
                throw new IOException("Unable to read file into a complete set", e);
            }
            handler.deleteFile();
            persisted = false;
        }
    }
    
    /**
     * Get an input stream. This should wrap handler.getInputStream with appropriate streams to handle reading the objects.
     * 
     * @return the input stream
     * @throws FileNotFoundException
     * @throws IOException
     */
    protected abstract InputStream getInputStream() throws IOException;
    
    /**
     * Get an output stream. This should wrap handler.getOutputStream with appropriate streams to handle writing the objects.
     * 
     * @return the output stream
     * @throws IOException
     */
    protected abstract OutputStream getOutputStream() throws IOException;
    
    /**
     * Write T to an output stream as returned by getOutputStream()
     * 
     * @param stream
     * @param t
     * @throws IOException
     */
    protected abstract void writeObject(OutputStream stream, E t) throws IOException;
    
    /**
     * Read T from an object input stream as returned by getInputStream()
     *
     * @param stream
     * @return
     * @throws IOException
     * @throws ClassNotFoundException
     */
    @SuppressWarnings("unchecked")
    protected abstract E readObject(InputStream stream) throws IOException;
    
    /**
     * Is this set persisted?
     */
    public boolean isPersisted() {
        return persisted;
    }
    
    /**
     * Get the size of the set. Note if the set has been persisted, then this may be an upper bound on the size.
     * 
     * @return the size upper bound
     */
    @Override
    public int size() {
        if (persisted) {
            try {
                return readSize();
            } catch (Exception e) {
                throw new IllegalStateException("Unable to get size from file", e);
            }
        } else {
            return set.size();
        }
    }
    
    @Override
    public boolean isEmpty() {
        // must attempt to read the first element to be sure if persisted
        try {
            first();
            return false;
        } catch (NoSuchElementException e) {
            return true;
        }
    }
    
    @SuppressWarnings("unchecked")
    @Override
    public boolean contains(Object o) {
        if (persisted) {
            E t = (E) o;
            for (Iterator<E> it = iterator(); it.hasNext();) {
                if (it.hasNext()) {
                    E next = it.next();
                    if (equals(next, t)) {
                        return true;
                    }
                }
            }
            return false;
        } else {
            return set.contains(o);
        }
    }
    
    @Override
    public Iterator<E> iterator() {
        if (persisted) {
            return new FileIterator();
        } else {
            return set.iterator();
        }
    }
    
    @Override
    public Object[] toArray() {
        if (persisted) {
            try {
                int size = readSize();
                InputStream stream = getInputStream();
                try {
                    Object[] data = new Object[size];
                    for (int i = 0; i < size; i++) {
                        data[i] = readObject(stream);
                    }
                    return data;
                } finally {
                    stream.close();
                }
            } catch (Exception e) {
                throw new IllegalStateException("Unable to read file into a complete set", e);
            }
        } else {
            return set.toArray();
        }
    }
    
    @SuppressWarnings({"unchecked"})
    @Override
    public <T> T[] toArray(T[] a) {
        if (persisted) {
            try {
                int size = readSize();
                InputStream stream = getInputStream();
                try {
                    T[] dest = a;
                    int i = 0;
                    for (; i < size; i++) {
                        T obj = (T) readObject(stream);
                        if (dest.length <= i) {
                            T[] newDest = (T[]) (Array.newInstance(a.getClass().getComponentType(), size));
                            System.arraycopy(dest, 0, newDest, 0, i);
                            dest = newDest;
                        }
                        dest[i] = obj;
                    }
                    // if not resized
                    if (dest == a) {
                        // ensure extra elements are set to null
                        for (; i < dest.length; i++) {
                            dest[i] = null;
                        }
                    }
                    return dest;
                } finally {
                    stream.close();
                }
            } catch (Exception e) {
                throw new IllegalStateException("Unable to read file into a complete set", e);
            }
        } else {
            return set.toArray(a);
        }
    }
    
    @Override
    public boolean add(E e) {
        if (persisted) {
            throw new IllegalStateException("Cannot add an element to a persisted FileSortedSet.  Please call load() first.");
        } else {
            return set.add(e);
        }
    }
    
    @Override
    public boolean remove(Object o) {
        if (persisted) {
            throw new IllegalStateException("Cannot remove an element to a persisted FileSortedSet.  Please call load() first.");
        } else {
            return set.remove(o);
        }
    }
    
    @SuppressWarnings("unchecked")
    @Override
    public boolean containsAll(Collection<?> c) {
        if (c.isEmpty()) {
            return true;
        }
        if (persisted) {
            try {
                SortedSet<E> all = new TreeSet<>(set.comparator());
                for (Object o : c) {
                    all.add((E) o);
                }
                int size = readSize();
                InputStream stream = getInputStream();
                try {
                    for (int i = 0; i < size; i++) {
                        E obj = readObject(stream);
                        if (all.remove(obj)) {
                            if (all.isEmpty()) {
                                return true;
                            }
                        }
                    }
                } finally {
                    stream.close();
                }
            } catch (Exception e) {
                throw new IllegalStateException("Unable to read file into a complete set", e);
            }
            return false;
        } else {
            return set.containsAll(c);
        }
    }
    
    @Override
    public boolean addAll(Collection<? extends E> c) {
        if (persisted) {
            throw new IllegalStateException("Unable to add to a persisted FileSortedSet.  Please call load() first.");
        } else {
            return set.addAll(c);
        }
    }
    
    @Override
    public boolean retainAll(Collection<?> c) {
        if (persisted) {
            throw new IllegalStateException("Unable to modify a persisted FileSortedSet.  Please call load() first.");
        } else {
            return set.retainAll(c);
        }
    }
    
    @Override
    public boolean removeAll(Collection<?> c) {
        if (persisted) {
            throw new IllegalStateException("Unable to remove from a persisted FileSortedSet.  Please call load() first.");
        } else {
            return set.removeAll(c);
        }
    }
    
    @Override
    public void clear() {
        if (persisted) {
            handler.deleteFile();
            persisted = false;
        } else {
            set.clear();
        }
    }
    
    @Override
    public Comparator<? super E> comparator() {
        return set.comparator();
    }
    
    @Override
    public SortedSet<E> subSet(E fromElement, E toElement) {
        if (persisted) {
            throw new IllegalStateException("Unable to subset a persisted FileSortedSet.  Please call load() first.");
        } else {
            return set.subSet(fromElement, toElement);
        }
    }
    
    @Override
    public SortedSet<E> headSet(E toElement) {
        if (persisted) {
            throw new IllegalStateException("Unable to subset a persisted FileSortedSet.  Please call load() first.");
        } else {
            return set.headSet(toElement);
        }
    }
    
    @Override
    public SortedSet<E> tailSet(E fromElement) {
        if (persisted) {
            throw new IllegalStateException("Unable to subset a persisted FileSortedSet.  Please call load() first.");
        } else {
            return set.tailSet(fromElement);
        }
    }
    
    @Override
    public E first() {
        boolean gotFirst = false;
        E first = null;
        if (persisted) {
            try {
                int size = readSize();
                InputStream stream = getInputStream();
                try {
                    if (size != 0) {
                        first = readObject(stream);
                        gotFirst = true;
                    }
                } catch (IOException ioe) {
                    QueryException qe = new QueryException(DatawaveErrorCode.FETCH_FIRST_ELEMENT_ERROR, ioe);
                    throw (NoSuchElementException) (new NoSuchElementException().initCause(qe));
                } finally {
                    stream.close();
                }
            } catch (Exception e) {
                QueryException qe = new QueryException(DatawaveErrorCode.FETCH_FIRST_ELEMENT_ERROR, e);
                throw (new IllegalStateException(qe));
            }
        } else if (!set.isEmpty()) {
            first = set.first();
            gotFirst = true;
        }
        if (!gotFirst) {
            QueryException qe = new QueryException(DatawaveErrorCode.FETCH_FIRST_ELEMENT_ERROR);
            throw (NoSuchElementException) (new NoSuchElementException().initCause(qe));
        } else {
            return first;
        }
    }
    
    @Override
    public E last() {
        boolean gotLast = false;
        E last = null;
        if (persisted) {
            try {
                int size = readSize();
                InputStream stream = getInputStream();
                try {
                    for (int i = 0; i < size; i++) {
                        last = readObject(stream);
                        gotLast = true;
                    }
                } finally {
                    stream.close();
                }
            } catch (Exception e) {
                throw new IllegalStateException("Unable to get last from file", e);
            }
        } else if (!set.isEmpty()) {
            last = set.last();
            gotLast = true;
        }
        if (!gotLast) {
            QueryException qe = new QueryException(DatawaveErrorCode.FETCH_LAST_ELEMENT_ERROR);
            throw (NoSuchElementException) (new NoSuchElementException().initCause(qe));
        } else {
            return last;
        }
    }
    
    /********* Some sub classes ***********/
    
    /**
     * This is the iterator for a persisted FileSortedSet
     * 
     * 
     * 
     */
    protected class FileIterator implements Iterator<E> {
        private int size = 0;
        private int index = 0;
        private InputStream stream = null;
        
        public FileIterator() {
            try {
                this.size = readSize();
                if (this.size == 0) {
                    cleanup();
                } else {
                    this.stream = getInputStream();
                }
            } catch (Exception e) {
                throw new IllegalStateException("Unable to read file", e);
            }
        }
        
        public void cleanup() {
            if (stream != null) {
                try {
                    stream.close();
                } catch (Exception e) {
                    // we tried...
                }
                stream = null;
            }
        }
        
        @Override
        public boolean hasNext() {
            if (stream == null) {
                return false;
            }
            if (index >= size) {
                cleanup();
            }
            return index < size;
        }
        
        @Override
        public E next() {
            if (!hasNext()) {
                QueryException qe = new QueryException(DatawaveErrorCode.FETCH_NEXT_ELEMENT_ERROR);
                throw (NoSuchElementException) (new NoSuchElementException().initCause(qe));
            }
            try {
                E o = readObject(stream);
                index++;
                if (index >= size) {
                    cleanup();
                }
                return o;
            } catch (Exception e) {
                throw new IllegalStateException("Unable to get next element from file", e);
            }
        }
        
        @Override
        public void remove() {
            throw new UnsupportedOperationException("Cannot remove elements from a persisted file.  Please call load() first.");
        }
        
        @Override
        protected void finalize() throws Throwable {
            cleanup();
            super.finalize();
        }
        
    }
    
    /********* Some utilities ***********/
    
    private boolean equals(E o1, E o2) {
        if (o1 == null) {
            return o2 == null;
        } else if (o2 == null) {
            return false;
        } else {
            if (set.comparator() == null) {
                return o1.equals(o2);
            } else {
                return set.comparator().compare(o1, o2) == 0;
            }
        }
    }
    
    @Override
    public String toString() {
        if (persisted) {
            return handler.toString();
        } else {
            return set.toString();
        }
    }
    
    /**
     * Extending classes must implement cloneable
     * 
     * @return A clone
     */
    public abstract FileSortedSet<E> clone();
    
    /**
     * An interface for a sorted set factory
     * 
     * @param <E>
     */
    public interface FileSortedSetFactory<E> {
        /**
         * factory method
         *
         * @param handler
         * @param persisted
         * @return a new instance
         */
        FileSortedSet<E> newInstance(SortedSetFileHandler handler, boolean persisted);
        
        /**
         * Factory method
         *
         * @param comparator
         * @param handler
         * @param persisted
         * @return a new instance
         */
        FileSortedSet<E> newInstance(Comparator<? super E> comparator, SortedSetFileHandler handler, boolean persisted);
        
        /**
         * Create an unpersisted sorted set (still in memory)
         *
         * @param set
         * @param handler
         * @return a new instance
         */
        FileSortedSet<E> newInstance(SortedSet<E> set, SortedSetFileHandler handler);
        
        /**
         * factory method
         *
         * @param set
         * @param handler
         * @param persist
         * @return a new instance
         * @throws IOException
         */
        FileSortedSet<E> newInstance(SortedSet<E> set, SortedSetFileHandler handler, boolean persist) throws IOException;
    }
    
}
