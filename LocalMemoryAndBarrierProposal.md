# DRAFT : I am tinkering with this doc as of 12/5/2011 #

There is a discussion list here http://groups.google.com/group/aparapi-discuss/browse_thread/thread/c544ffd2aec7ff50 for this as well.

At present all Aparapi data is considered global.  Primitive arrays (such as `int buf[]`) are mapped to `__global` pointers (such as `__global int *buf`).

Although this makes Aparapi easy to use (especially to Java developers not used to being exposed to tiered memory hierarchies), it does limit the ability of the 'power developer' wishing to extract more performance from Aparapi on the GPU.



This [page](http://www.amd.com/us/products/technologies/stream-technology/opencl/pages/opencl-intro.aspx?cmpid=cp_article_2_2010) from AMD's website shows the different types of memory that OpenCL programmers need to worry about.

![http://www.amd.com/PublishingImages/Public/Graphic_Illustrations/375WJPEG/47039A_OpenCLMemoryModel_375W.jpg](http://www.amd.com/PublishingImages/Public/Graphic_Illustrations/375WJPEG/47039A_OpenCLMemoryModel_375W.jpg)

Curently in Aparapi our primitive Java arrays are stored in host memory and are copied to Global memory (the RAM of the GPU card).

Because local memory is 'closer' to the compute devices, the use of local memory on OpenCL can lead to much more performant code as the cost of fetching from local memory is much lower.

---

## Some History ##

In the alpha version of Aparapi we allowed the @Local annotation to be applied to primitive arrays (not sadly captured final fields).

```
int globalArray[] = new int[512];
Kernel kernel = new Kernel(){
   @Local int localArray[] = new int[64];
   @Override public void run(){
       localArray[getLocalId())=getLocalId();
       localBarrier();
       globalArray[getGlobalId()] = localArray[getLocalId());
       
   }
}
```

Sure the code is kind of nonesense, but it did show that we could declare local buffers, create them and use local barriers to wait for the group to all complete and then use the local buffers.

The main issues with the alpha version were:
  1. The size of any local buffers needed to be proportional to the groupsize.
  1. The size of group was not known until the kernel is executed
  1. It was tricky to emulate groups in Java and to make local barriers work, these all cost cycles in the Java version and usually resulted in performance loss.

The solution we came to in alpha was to have a callback (sadly called `setSizes(int globalSize, int localSize)`) in the base Kernel class.  This callback was called we were confident we knew what globalSize and localSize were (we know the algorithm used by AMD OpenCL runtime which is based on common AMD device configurations).  The kernel writer was expected to use this callback to create local buffers.

```
int globalArray[] = new int[512];
Kernel kernel = new Kernel(){
   @Local int localArray[] = null;
   @Override public void setSizes(int _globalSize, int _localSize){
      localArray = new int[_localSize];
      super.setSize(_globalSize, _localSize);
   }
   @Override public void run(){
       localArray[getLocalId())=getLocalId();
       localBarrier();
       globalArray[getGlobalId()] = localArray[getLocalId());
       
   }
}
```

This worked well for GPU (for single dimension executions - remember we don't map `get_local_size(1)` or `get_local_size(2)`), but we probably should. There is a separate AccessingMultiDimNDRangeProposal page looking into that.

For JTP mode (fallback) we can honor the callback, we can create a barrier (across all threads - so a group was basically always the # of cores), but this just slows JTP execution.

Note also the rookie API mistakes.

  1. The implementer was obliged to invoke `super.setSizes()`, that was a big mistake.
  1. The name `setSizes` was bad, it should have been `sizeCallback()` or something
  1. Should have been `protected`.

These three bad API decisions :) meant lots of bugs. Folk would override and forget to call `super.setSizes()` also because it was public folk would call `kernel.setSizes(1024, 23)` assuming this would set global and localsize and finally people would override but change the value passed down to `super.setSizes()`.  This was all the result of these bad API decisions.

Before open sourcing I removed all this code (well remnants remain in some KernelRunner static fields and in the JNI layer bitfields for args), with the idea of addressing it again later when we had a better idea whether people would want to use local memory and or barriers.

Now might be that time ;)

So this page is intended to look at this problem again and hopefully come up with a better solution.


---


Some proposals.

One proposal of course is to retread the alpha steps and reintroduce the notion of @Local annotations (for Kernel buffers - won't work for captured final fields used by anonymous inner classes).

We will cleanup the callback name, undo the API mistakes and allow code such as this.

```
int globalArray[] = new int[512];
Kernel kernel = new Kernel(){
   @Local int localArray[] = null;
   @Override protected void preExecuteCallback(int _globalSize, int _localSize){
      localArray = new int[_localSize];
   }
   @Override public void run(){
       localArray[getLocalId())=getLocalId();
       localBarrier();
       globalArray[getGlobalId()] = localArray[getLocalId());
       
   }
}
```


Another alternate is to use the annotations themselves to size the local buffers.

They can either use absolute values or can use multipliers/divisors based on other runtime OpenCL values.

For example

`  @Local(size=512) int localArray[]; `

This is an absolute buffer local array which is to be 512\*sizeof(int).

If you wondering why not just use `@Local int[] localArray = new int[512];` for this then remember that when we execute on the GPU we don't pass the array, we just tell the OpenCL how big the buffer is. If we declared the buffer as above and the code was actually executed on the GPU the array would never be used. If we fallback in JTP mode the KernelRunner can just allocate the actual array for us.

It is common for local buffers to be some function of the group size or local size. In the example above we create a buffer 1:1 mapped to this.

We could just use 'scale' properties attached to the @Local annotation to signal this ratio. Either as a multiplier

```
  // Provide a localArray which will be 2*get_local_size(0)
  @Local(localMultiplier=2) int localArray[]; 
```

or as a divisor
```
  // Provide a localArray which will be get_local_size(0)/2
  @Local(localDivisor=2) int localArray[]; 
```

Then just prior to execution the runtime can size all local buffers accordingly.

## Latest thoughts ##
After discussions with some OpenCL guru's I am rethinking the 'ask' here.  All of the design decisions so far were based on the notion that it is possible to predict the value returned by get\_group\_size

From [clEnqueuNDRangeKernel](http://www.khronos.org/registry/cl/sdk/1.2/docs/man/xhtml/clEnqueueNDRangeKernel.html) we get the following definitions

### `work_dim` ###
> The number of dimensions used to specify the global work-items and work-items in the work-group. work\_dim must be greater than zero and less than or equal to `CL_DEVICE_MAX_WORK_ITEM_DIMENSIONS`.

### `global_work_offset` ###
> global\_work\_offset can be used to specify an array of work\_dim unsigned values that describe the offset used to calculate the global ID of a work-item. If global\_work\_offset is NULL, the global IDs start at offset (0, 0, ... 0).

### `global_work_size` ###
> Points to an array of work\_dim unsigned values that describe the number of global work-items in work\_dim dimensions that will execute the kernel function. The total number of global work-items is computed as `global_work_size[0] *...* global_work_size[work_dim - 1]`.

### `local_work_size` ###
> Points to an array of work\_dim unsigned values that describe the number of work-items that make up a work-group (also referred to as the size of the work-group) that will execute the kernel specified by kernel. The total number of work-items in a work-group is computed as `local_work_size[0] *... * local_work_size[work_dim - 1]`. The total number of work-items in the work-group must be less than or equal to the `CL_DEVICE_MAX_WORK_GROUP_SIZE` value specified in table of OpenCL Device Queries for clGetDeviceInfo and the number of work-items specified in local\_work\_size[0](0.md),... local\_work\_size[- 1](work_dim.md) must be less than or equal to the corresponding values specified by `CL_DEVICE_MAX_WORK_ITEM_SIZES[0],.... CL_DEVICE_MAX_WORK_ITEM_SIZES[work_dim - 1]`. The explicitly specified local\_work\_size will be used to determine how to break the global work-items specified by global\_work\_size into appropriate work-group instances. If local\_work\_size is specified, the values specified in global\_work\_size[0](0.md),... global\_work\_size[- 1](work_dim.md) must be evenly divisible by the corresponding values specified in `local_work_size[0],... local_work_size[work_dim - 1]`.

> `local_work_size` can also be a `NULL` value in which case the OpenCL implementation will determine how to be break the global work-items into appropriate work-group instances.









