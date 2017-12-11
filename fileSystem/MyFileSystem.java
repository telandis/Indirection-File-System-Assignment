package fileSystem;
import java.io.*;

/**
 * Unit tests are in {@see TestMyFileSystem}. See MyFileSystem.java.
 */
public class MyFileSystem implements FileSystem {
    public Disk       disk       = new Disk();
    public FileTable  fileTable  = new FileTable();
    public SuperBlock superBlock = new SuperBlock();
    public FreeMap    freeMap;

    /**
     * Reading from or writing to a file.
     */
    private enum MODE { w, r };

    /**
     * Construct a new FileSystem. You are responsible for calling
     * formatDisk on the new FileSystem if necessary.
     */
    public MyFileSystem() throws IOException {
        disk.read(0, superBlock);
        initFreeMap();
    }
    
    public int formatDisk(int size, int isize) throws IOException {
        // The total size of the file system cannot be larger than the
        // maximum size of the disk.
        //
        if(size > Disk.NUM_BLOCKS) {
            System.err.println("Size exceeds disk size of " + Disk.NUM_BLOCKS);
            return -1;
        }
        
        // Calculate the number of blocks needed for the freemap (may
        // be 0 if the entire free map fits within the superblock.
        //
        int extra = (size - isize - 1) - superBlock.freeMap.length * 8;
        int msize = (int)Math.max(0, Math.ceil(extra / 8.0 / Disk.BLOCK_SIZE));
        
        // We require that the size of the metadata not exceed the
        // size of the file system.
        //
        if(size - msize - isize - 1 < 0) {
            System.err.println("Metadata will not fit in file system");
            return -1;
        }

        // Initialize and write the superblock.
        superBlock.size  = size;
        superBlock.isize = isize;
        superBlock.msize = msize;
        disk.write(0, superBlock);

        // Write empty FreeMapBlocks (if needed) and InodeBlocks, with
        // FreeMapBlocks immediately following the SuperBlock and
        // InodeBlocks immediately following the FreeMapBlocks.
        //
        if(superBlock.mblock0() > 0)
            for(int i = superBlock.mblock0(); i < superBlock.iblock0(); ++i)
                disk.write(i, new FreeMapBlock());
        for(int i = superBlock.iblock0(); i < superBlock.dblock0(); ++i)
            disk.write(i, new InodeBlock());

        // Set up the free map again (because we changed file system
        // metadata since the constructor was called).
        //
        initFreeMap();

        return 0;
    }
    
    public int shutdown() throws IOException {
        // Save any free map blocks that haven't been written
        freeMap.save();

        // Close any open files
        for(int fd = 0; fd < FileTable.MAX_FILES; ++fd)
            if(fileTable.isValid(fd))
                close(fd);

        // Stop the disk and end
        disk.stop(false);
        return 0;
    }
    
    public int create() throws IOException {
        // Try to get a free file descriptor.
        //
        int fd = fileTable.allocate();
        if(fd < 0)
            return -1;

        // Try to find an inode for the new file.
        //
        InodeBlock block = new InodeBlock();
        int inumber = 1; // inumbers start at 1, not 0
        for(int n = superBlock.iblock0(); n < superBlock.dblock0(); ++n) {
            disk.read(n, block);
            for(int o = 0; o < InodeBlock.COUNT; ++o, ++inumber) {
                if(block.inodes[o].flags == 0) {
                    block.inodes[o].allocate();
                    fileTable.add(block.inodes[o], inumber, fd);
                    disk.write(n, block);
                    return fd;
                }
            }
        }

        // Could not find a free inode, so release our file
        // descriptor, print an error message, and finish.
        //
        fileTable.free(fd);
        System.err.println("Out of files");
        return -1;
    }
    
    public int open(int inumber) throws IOException {
        if(! inumberIsValid(inumber))
            return -1;
        
        // Try to get a free file descriptor.
        //
        int fd = fileTable.allocate();
        if(fd < 0)
            return -1;

        // Get the requested inode from disk.
        //
        InodeBlock inodeBlock = new InodeBlock();
        disk.read(inumberToBlockNum(inumber), inodeBlock);
        Inode inode = inodeBlock.inodes[inumberToOffset(inumber)];

        // If the inode is allocated, associate the inode with the
        // file descriptor, and then return the fd. Otherwise, there
        // was an error so we should release the file descriptor and
        // return -1.
        //
        if(inode.flags != 0) {
            fileTable.add(inode, inumber, fd);
            return fd;
        }
        fileTable.free(fd);
        System.err.println("File " + inumber + " does not exist");
        return -1;
    }
    
    public int inumber(int fd) throws IOException {
        return fileTable.getInumber(fd);
    }
    
    public int read(int fd, byte[] buffer) throws IOException {
        if(! fileDescriptorIsValid(fd))
            return -1;

        DirectBlock block;
        int len, off = 0, limit = getReadLimit(fd, buffer.length);
        for(off = 0; off < limit; off += len) {
            block = getDirectBlock(fd, MODE.r);
            len = block.copyTo(buffer, off); // may copy some garbage in
            seek(fd, len, Whence.SEEK_CUR);
        }
        return limit;
    }
    
    public int write(int fd, byte[] buffer) throws IOException {
        if(! fileDescriptorIsValid(fd))
            return -1;

        DirectBlock block;
        int len, off = 0;
        for(off = 0; off < buffer.length; off += len) {
            if((block = getDirectBlock(fd, MODE.w)) == null) {
                System.err.println("File system is full");
                return -1;
            }
            len = block.copyFrom(buffer, off);
            seek(fd, len, Whence.SEEK_CUR);
            updateFileSize(fd);
            block.save();
        }
        return buffer.length;
    }
    
    public int seek(int fd, int offset, Whence whence) throws IOException {
        if(! fileDescriptorIsValid(fd))
            return -1;
        
        Inode inode = fileTable.getInode(fd);
        int ptr;
        
        switch(whence) {
            case SEEK_SET:
                ptr = offset;
                break;
            case SEEK_END:
                ptr = offset + inode.size;
                break;
            case SEEK_CUR:
                ptr = offset + fileTable.getSeekPointer(fd);
                break;
            default:
                return -1;
        }
        if(ptr < 0) {
            System.err.println("Cannot seek to offset < 0");
            return -1;
        }
        fileTable.setSeekPointer(fd, ptr);
        return ptr;
    }
    
    public int close(int fd) throws IOException {
        if(! fileDescriptorIsValid(fd))
            return -1;

        // Read the InodeBlock in, modify it, and write it back out.
        //
        InodeBlock inodeBlock = new InodeBlock();
        int inumber = fileTable.getInumber(fd);
        disk.read(inumberToBlockNum(inumber), inodeBlock);
        inodeBlock.inodes[inumberToOffset(inumber)] = fileTable.getInode(fd);
        disk.write(inumberToBlockNum(inumber), inodeBlock);

        // Free the file descriptor and return successfully.
        //
        fileTable.free(fd);
        return 0;
    }
    
    public int delete(int inumber) throws IOException {
        // Disallow deleting of open files.
        //
        int fd;
        if((fd = fileTable.getFdFromInumber(inumber)) != -1) {
            System.err.println("Cannot delete open file (fd = " + fd + ")");
            return -1;
        }

        // Get inode for this file.
        //
        InodeBlock inodeBlock = new InodeBlock();
        disk.read(inumberToBlockNum(inumber), inodeBlock);
        Inode inode = inodeBlock.inodes[inumberToOffset(inumber)];

        // Free all direct blocks in the free map. No need to clear
        // the inode pointers, they are cleared when allocating a new
        // file.
        //
        for(int i = 0; i < inode.ptr.length; ++i)
            if(inode.ptr[i] != 0)
                freeMap.clear(inode.ptr[i]);
        freeMap.save();

        // Mark the inode as free and write it to disk.
        //
        inode.flags = 0;
        disk.write(inumberToBlockNum(inumber), inodeBlock);
        return 0;
    }

    /**
     * Initialize the freeMap instance. Should be called at the end of
     * the constructor and from formatDisk.
     */
    private void initFreeMap() {
        freeMap = new FreeMap(disk, superBlock);
    }

    /**
     * Ensure that the fd is within the valid range and refers to an
     * open file. Prints an error message if it is invalid.
     *
     * @return boolean true if fd is valid, false otherwise
     */
    private boolean fileDescriptorIsValid(int fd) {
        if(( fd < 0                         ||
             fd >= FileTable.MAX_FILES      ||
             fileTable.getInode(fd) == null )) {
            System.err.println("File descriptor " + fd + " is invalid");
            return false;
        }
        return true;
    }

    /**
     * Ensure that the inumber is within the valid range. Prints an
     * error message if it is invalid.
     *
     * @return boolean true if inumber is valid, false otherwise
     */
    private boolean inumberIsValid(int inumber) {
        if(inumber <= 0 || inumber >= superBlock.isize * InodeBlock.COUNT) {
            System.err.println("inumber " + inumber + " is invalid");
            return false;
        }
        return true;
    }

    /**
     * Get a DirectBlock object representing the direct block given
     * the current seek position in the open file identified by fd. A
     * DirectBlock references the direct block and offset within that
     * block containing the current seek position.
     *
     * If the current seek position is within a hole or beyond the end
     * of a file, then if create is true then a block will be
     * allocated to fill the hole. If there is no more free space in
     * the file system, null will be returned. If the seek position is
     * in a hole and create is false, then a block containing zeroes
     * will be returned.
     *
     * @param   fd          valid file descriptor of an open file
     * @param   mode        MODE.w if holes should be filled, MODE.r
     *                      otherwise (holes will be read as blocks
     *                      of all zeros)
     * @returns DirectBlock block and offset in that block where the
     *                      seek position of fd can be found
     */
    private DirectBlock getDirectBlock(int fd, MODE mode) {//FIXME!!!
    	Inode inode   = fileTable.getInode(fd);
        int seekPtr   = fileTable.getSeekPointer(fd);
        int blockNum  = seekPtr / Disk.BLOCK_SIZE;
        int blockOff  = seekPtr % Disk.BLOCK_SIZE;
        
        boolean fresh;
        if(blockNum < 10) {//if first ten direct blocks aka no indirection
        	fresh = inode.ptr[blockNum] == 0;//all original code that was in the file
        	if(fresh)
                if(mode == MODE.r)
                    return DirectBlock.hole;
                else if((inode.ptr[blockNum] = freeMap.find()) == 0)
                    return null;
            return new DirectBlock(disk, inode.ptr[blockNum], blockOff, fresh);
        } else if(blockNum > 9 && blockNum < IndirectBlock.COUNT+10) {//single indirection case
        	IndirectBlock blk = new IndirectBlock();
        	int indirectindex = blockNum - 10;
        	if(inode.ptr[10] == 0) {//if indirect block does not exist
        		if(mode == MODE.r) {//read mode
        			return DirectBlock.hole;//return hole
        		} else { //write
        			int loc = freeMap.find();
        			if(loc == 0) {//see if freeMap returns anything, if 0 then return null
        				return null;
        			}
        			int loc2 = freeMap.find();
        			if(loc2 == 0) {//see if freeMap returns anything, if 0 then return null
        				return null;
        			}
        			inode.ptr[10] = loc;//set address of allocated space for indirect block
        			
        			blk.ptr[indirectindex] = loc2;//set address of allocated space for direct block
        			disk.write(loc, blk);//writes to address loc in disk the new indirect block
        			freeMap.save();//lets freeMap know that space given is being used
        			return new DirectBlock(disk, loc2, blockOff, true);
        		}
        	} else {
        		if(mode == MODE.r) {//check if read or write mode
        			disk.read(inode.ptr[10], blk);//reads from disk indirect block and loads it
        			if(blk.ptr[indirectindex] == 0) {//checks if indirect block exists, if not returns hole
        				return DirectBlock.hole;
        			}
        			return new DirectBlock(disk, blk.ptr[indirectindex], blockOff, false);
        		} else { //write
        			disk.read(inode.ptr[10], blk);//reads from disk indirect block and loads it
        			if(blk.ptr[indirectindex] == 0) {
        				int loc = freeMap.find();
        				if(loc == 0) {
        					return null;
        				}
        				blk.ptr[indirectindex] = loc;
        				freeMap.save();
        				disk.write(inode.ptr[10], blk);//writes to address loc in disk the new indirect block
        				return new DirectBlock(disk, blk.ptr[indirectindex], blockOff, true);
        			}
        			return new DirectBlock(disk, blk.ptr[indirectindex], blockOff, false);
        		}
        	}
        } else if(blockNum > IndirectBlock.COUNT+9 && blockNum < 10 + IndirectBlock.COUNT + (IndirectBlock.COUNT * IndirectBlock.COUNT)) {
        	//if it is double indirection
        	IndirectBlock blk1 = new IndirectBlock();
    		IndirectBlock blk2 = new IndirectBlock();
    		int indirectIndex1 = (blockNum - 10 - IndirectBlock.COUNT)/IndirectBlock.COUNT;
    		int indirectIndex2 = (blockNum - 10 - IndirectBlock.COUNT) % IndirectBlock.COUNT;
        	if(inode.ptr[11] == 0) {//check if indirect block exists
        		if(mode == MODE.r) {
        			return DirectBlock.hole;//returns hole if read mode and doesn't exist
        		} else {//write
            		int loc1 = freeMap.find();
            		if(loc1 == 0) {
            			return null;
            		}
            		inode.ptr[11] = loc1;
            		int loc2 = freeMap.find();
            		if(loc2 == 0) {
            			return null;
            		}
            		blk1.ptr[indirectIndex1] = loc2;
            		int loc3 = freeMap.find();
            		if(loc3 == 0) {
            			return null;
            		}
            		blk2.ptr[indirectIndex2] = loc3;
            		disk.write(loc1, blk1);//writes to address loc in disk the new indirect block
            		disk.write(loc2, blk2);//writes to address loc in disk the new indirect block
            		freeMap.save();
            		return new DirectBlock(disk, loc3, blockOff, true);
            		//creates all indirect blocks needed if there is enough space, otherwise returns null
        		}
        	} else {
        		//
        		if(mode == MODE.r) {
        			disk.read(inode.ptr[11], blk1);
        			//since read mode, checks if there are any holes. If so return hole
        			if(blk1.ptr[indirectIndex1] == 0) {
        				return DirectBlock.hole;
        			}
        			disk.read(blk1.ptr[indirectIndex1], blk2);
        			if(blk2.ptr[indirectIndex2] == 0) {
        				return DirectBlock.hole;
        			}
        			return new DirectBlock(disk, blk2.ptr[indirectIndex2], blockOff, false);//returns direct block at index
        		} else {//write
        			disk.read(inode.ptr[11], blk1);
        			if(blk1.ptr[indirectIndex1] == 0) {
        				int loc1 = freeMap.find();
        				if(loc1 == 0) {
        					return null;
        				}
        				blk1.ptr[indirectIndex1] = loc1;
        				int loc2 = freeMap.find();
        				if(loc2 == 0) {
        					return null;
        				}
        				blk2.ptr[indirectIndex2] = loc2;
        				freeMap.save();
        				disk.write(inode.ptr[11], blk1);//writes to address loc in disk the new indirect block
                		disk.write(loc1, blk2);//writes to address loc in disk the new indirect block
        				return new DirectBlock(disk, loc2, blockOff, true);
        				//creates all the necessary indirect blocks if enough space, otherwise return null
        			} else {
        				disk.read(blk1.ptr[indirectIndex1], blk2);//reads from disk indirect block and loads it
        				if(blk2.ptr[indirectIndex2] == 0) {
        					int loc1 = freeMap.find();
        					if(loc1 == 0) {
        						return null;
        					}
        					blk2.ptr[indirectIndex2] = loc1;
        					freeMap.save();
                    		disk.write(blk1.ptr[indirectIndex1], blk2);//writes to address loc in disk the new indirect block
        					return new DirectBlock(disk, loc1, blockOff, true);
        				}
        				//creates all the necessary indirect blocks if enough space, otherwise return null
        				return new DirectBlock(disk, blk2.ptr[indirectIndex2], blockOff, false);
        			}
        		}
        	}
        } else {//case of triple indirection
        	IndirectBlock blk1 = new IndirectBlock();
    		IndirectBlock blk2 = new IndirectBlock();
    		IndirectBlock blk3 = new IndirectBlock();
    		//calculate indexes of each indirect block to find given index
    		int indirectIndex1 = ((blockNum - 16522)/IndirectBlock.COUNT)/IndirectBlock.COUNT;
    		int indirectIndex2 = ((blockNum - 16522) - (indirectIndex1*IndirectBlock.COUNT*IndirectBlock.COUNT))/128;
    		int indirectIndex3 = (blockNum - 16522) % IndirectBlock.COUNT;
        	if(inode.ptr[12] == 0) {
        		if(mode == MODE.r) {
        			return DirectBlock.hole;//returns hole if mode r and hole is found
        		} else {
            		int loc1 = freeMap.find();
            		if(loc1 == 0) {
            			return null;
            		}
            		inode.ptr[12] = loc1;
            		int loc2 = freeMap.find();
            		if(loc2 == 0) {
            			return null;
            		}
            		blk1.ptr[indirectIndex1] = loc2;
            		int loc3 = freeMap.find();
            		if(loc3 == 0) {
            			return null;
            		}
            		blk2.ptr[indirectIndex2] = loc3;
            		int loc4 = freeMap.find();
            		if(loc4 == 0) {
            			return null;
            		}
            		blk3.ptr[indirectIndex3] = loc4;
            		freeMap.save();
            		disk.write(loc1, blk1);//writes to address loc in disk the new indirect block
            		disk.write(loc2, blk2);//writes to address loc in disk the new indirect block
            		disk.write(loc3, blk3);//writes to address loc in disk the new indirect block
            		return new DirectBlock(disk, loc4, blockOff, true);
            		//creates all the indirect blocks if enough space, otherwise returns null
        		}
        	} else {
        		if(mode == MODE.r) {
        			disk.read(inode.ptr[12], blk1);//reads from disk indirect block and loads it
        			if(blk1.ptr[indirectIndex1] == 0) {
        				return DirectBlock.hole;
        			}
        			disk.read(blk1.ptr[indirectIndex1], blk2);//reads from disk indirect block and loads it
        			if(blk2.ptr[indirectIndex2] == 0) {
        				return DirectBlock.hole;
        			}
        			disk.read(blk2.ptr[indirectIndex3], blk3);//reads from disk indirect block and loads it
        			if(blk3.ptr[indirectIndex3] == 0) {
        				return DirectBlock.hole;
        			}
        			//checks if there are any holes, if so returns hole
        			return new DirectBlock(disk, blk3.ptr[indirectIndex3], blockOff, false);
        		} else {//write
        			disk.read(inode.ptr[12], blk1);//reads from disk indirect block and loads it
        			if(blk1.ptr[indirectIndex1] == 0) {
        				int loc1 = freeMap.find();
        				if(loc1 == 0) {
        					return null;
        				}
        				blk1.ptr[indirectIndex1] = loc1;
        				int loc2 = freeMap.find();
        				if(loc2 == 0) {
        					return null;
        				}
        				blk2.ptr[indirectIndex2] = loc2;
        				int loc3 = freeMap.find();
        				if(loc3 == 0) {
        					return null;
        				}
        				blk3.ptr[indirectIndex3] = loc3;
        				freeMap.save();
        				disk.write(inode.ptr[12], blk1);//writes to address loc in disk the new indirect block
                		disk.write(loc1, blk2);//writes to address loc in disk the new indirect block
                		disk.write(loc2, blk3);//writes to address loc in disk the new indirect block
                		return new DirectBlock(disk, loc3, blockOff, true);
                		//creates all indirect blocks to fill holes, if not enough space returns null
        			} else {
        				disk.read(blk1.ptr[indirectIndex1], blk2);//reads from disk indirect block and loads it
        				if(blk2.ptr[indirectIndex2] == 0) {
        					int loc2 = freeMap.find();
            				if(loc2 == 0) {
            					return null;
            				}
            				blk2.ptr[indirectIndex2] = loc2;
            				int loc3 = freeMap.find();
            				if(loc3 == 0) {
            					return null;
            				}
            				blk3.ptr[indirectIndex3] = loc3;
            				freeMap.save();
                    		disk.write(blk1.ptr[indirectIndex1], blk2);//writes to address loc in disk the new indirect block
                    		disk.write(loc2, blk3);//writes to address loc in disk the new indirect block
                    		return new DirectBlock(disk, loc3, blockOff, true);
                    		//creates all indirect blocks to fill holes, if not enough space returns null
        				} else {
        					disk.read(blk2.ptr[indirectIndex2], blk3);//reads from disk indirect block and loads it
        					if(blk3.ptr[indirectIndex3] == 0) {
                				int loc3 = freeMap.find();
                				if(loc3 == 0) {
                					return null;
                				}
                				blk3.ptr[indirectIndex3] = loc3;
                				freeMap.save();
                        		disk.write(blk2.ptr[indirectIndex2], blk3);//writes to address loc in disk the new indirect block
                        		return new DirectBlock(disk, loc3, blockOff, true);
        					}
        					//creates all indirect blocks to fill holes, if not enough space returns null
        					return new DirectBlock(disk, blk3.ptr[indirectIndex3], blockOff, false);
        				}
        			}
        			//return new DirectBlock(disk, blk3.ptr[indirectIndex3], blockOff, false);
        		}
        	}
        }
    
        // The blockNum is a logical block number referring to a
        // pointer in the inode.
        

    }

    /**
     * Convert an inumber to the number of the InodeBlock that
     * contains it.
     *
     * @param inumber inumber of inode to locate
     * @return int block number of InodeBlock
     */
    private int inumberToBlockNum(int inumber) {
        return superBlock.iblock0() + (inumber - 1) / InodeBlock.COUNT;
    }

    /**
     * Convert an inumber to its offset within its InodeBlock.
     *
     * @param inumber inumber of the inode to locate
     * @return int offset of inode within its InodeBlock
     */
    private int inumberToOffset(int inumber) {
        return (inumber - 1) % InodeBlock.COUNT;
    }

    /**
     * Update the size of a file if needed so that it is always at
     * least as large as the current seek pointer.
     */
    private void updateFileSize(int fd) {
        int currentSize = fileTable.getInode(fd).size;
        int seekPointer = fileTable.getSeekPointer(fd);
        if(seekPointer > currentSize)
            fileTable.setFileSize(fd, seekPointer);
    }

    /**
     * Get the maximum number of bytes that can be read from open file
     * fd into a buffer of length len. If the seek pointer is beyond
     * the end of the file, always returns 0 (since nothing can be
     * read beyond the end of a file).
     */
    private int getReadLimit(int fd, int len) {
        int rest = fileTable.getInode(fd).size - fileTable.getSeekPointer(fd);
        return Math.max(0, Math.min(len, rest));
    }
}
