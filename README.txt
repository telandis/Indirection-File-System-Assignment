Bitwise.java - 
I used various bitwise operations with the bit mask to complete all the methods.For the toString method, I used a for loop that checks all 8 bits in the byte and checks each for if it is 0 or 1, then adds it to a initially empty string.

MyFileSystem.java - 

For single indirection, I would check if the indirect block exists or not. If it does not, if mode is read return hole. Otherwise create a new indirect block if there is space available in the free map. If not return null. Then write the changes made to disk and return the new direct block.

If indirect block does exist, then I read from disk the indirect block and load it. If mode is read, then i return a direct block created from the proper index of indirect block.
If mode is write and the direct block at the index of indirect block doesnt exist yet, then I check the free map if there is space available, then make a new one and write it to disk. If direct block already exists, then I return the direct block.

For double and triple indirection, I more or less apply the same rules of single indirection, with some caveats. They are basically calling single indirection on multiple indirect blocks. If holes are found during write, all the necessary indirect blocks are created given enough space, if not enough space then null is returned.

Refer to comments in code for any more information.