package com.mumfrey.liteloader.core.event;

import java.io.Closeable;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import net.minecraft.launchwrapper.IClassTransformer;
import net.minecraft.launchwrapper.Launch;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import com.mumfrey.liteloader.core.runtime.Obf;
import com.mumfrey.liteloader.interfaces.FastIterableDeque;
import com.mumfrey.liteloader.util.log.LiteLoaderLogger;

/**
 * HandlerList is a generic class which supports baking a list of event handlers into a dynamic inner
 * class for invokation at runtime.
 * 
 * @author Adam Mummery-Smith
 *
 * @param <T>
 */
public class HandlerList<T> extends LinkedList<T> implements FastIterableDeque<T>
{
	private static final long serialVersionUID = 1L;

	private static final int MAX_UNCOLLECTED_CLASSES = 5000;
	
	private static int uncollectedHandlerLists = 0;
	
	/**
	 * Enum for logic operations supported between handlers which return bool
	 */
	public enum ReturnLogicOp
	{
		/**
		 * Logical OR applied between handlers, return FALSE unless one or more handlers returns TRUE 
		 */
		OR(true, false),
		
		/**
		 * Logical OR, returns TRUE at the first handler to return TRUE and doesn't process any further handlers
		 */
		OR_BREAK_ON_TRUE(true, true),
		
		/**
		 * Logical OR, but with the difference than an EMPTY handler list will return TRUE 
		 */
		OR_ASSUME_TRUE(true, false, true),
		
		/**
		 * Logical AND, returns TRUE if the list is empty or if all handlers return TRUE 
		 */
		AND(false, false),
		
		/**
		 * Logical AND, returns FALSE at the first handler to return FALSE and doesn't process any further handlers 
		 */
		AND_BREAK_ON_FALSE(false, true);
		
		private final boolean isOr;
		
		private final boolean breakOnMatch;
		
		private final boolean assumeTrue;
		
		private ReturnLogicOp(boolean isOr, boolean breakOnMatch)
		{
			this(isOr, breakOnMatch, false);
		}
		
		private ReturnLogicOp(boolean isOr, boolean breakOnMatch, boolean assumeTrue)
		{
			this.isOr = isOr;
			this.breakOnMatch = breakOnMatch;
			this.assumeTrue = assumeTrue;
		}
		
		boolean isOr()
		{
			return this.isOr;
		}
		
		public boolean breakOnMatch()
		{
			return this.breakOnMatch;
		}
		
		boolean assumeTrue()
		{
			return this.assumeTrue;
		}
	}

	/**
	 * Type of the interface for objects in this handler list
	 */
	private final Class<T> type;

	/**
	 * 
	 */
	private final ReturnLogicOp logicOp;
	
	/**
	 * Current baked handler list, we cook them at gas mark 5 for 30 minutes in a disposable classloader whic
	 * also handles the transformation for us
	 */
	private BakedHandlerList<T> bakedHandler;
	
	/**
	 * @param type
	 */
	public HandlerList(Class<T> type)
	{
		this(type, ReturnLogicOp.AND_BREAK_ON_FALSE);
	}
	
	/**
	 * @param type
	 * @param logicOp Logical operation to apply to interface methods which return boolean
	 */
	public HandlerList(Class<T> type, ReturnLogicOp logicOp)
	{
		if (!type.isInterface())
		{
			throw new IllegalArgumentException("HandlerList type argument must be an interface");
		}	
		
		this.type = type;
		this.logicOp = logicOp;
	}
	
	/**
	 * Returns the baked list of all listeners
	 * 
	 * @return
	 */
	@Override
	public T all()
	{
		if (this.bakedHandler == null)
		{
			this.bake();
		}
		
		return this.bakedHandler.get();
	}

	/**
	 * Bake the current handler list
	 */
	protected void bake()
	{
		HandlerListClassLoader<T> classLoader = new HandlerListClassLoader<T>(this.type, this.logicOp);
		this.bakedHandler = classLoader.newHandler(this);
		if (classLoader instanceof Closeable)
		{
			try
			{
				((Closeable)classLoader).close();
			}
			catch (IOException ex) {}
		}
	}

	/**
	 * Invalidate current baked list
	 */
	@Override
	public void invalidate()
	{
		if (this.bakedHandler == null)
		{
			return;
		}
		
		this.bakedHandler = null;
		HandlerList.uncollectedHandlerLists++;
		if (HandlerList.uncollectedHandlerLists > HandlerList.MAX_UNCOLLECTED_CLASSES)
		{
			System.gc();
			HandlerList.uncollectedHandlerLists = 0;
		}
	}

	/* (non-Javadoc)
	 * @see java.util.LinkedList#add(java.lang.Object)
	 */
	@Override
	public boolean add(T listener)
	{
		if (!this.contains(listener))
		{
			super.add(listener);
			this.invalidate();
		}
		
		return true;
	}
	
	/* (non-Javadoc)
	 * @see java.util.LinkedList#offer(java.lang.Object)
	 */
	@Override
	public boolean offer(T listener)
	{
		return this.add(listener);
	}
	
	/* (non-Javadoc)
	 * @see java.util.LinkedList#offerFirst(java.lang.Object)
	 */
	@Override
	public boolean offerFirst(T listener)
	{
		this.addFirst(listener);
		return true;
	}
	
	/* (non-Javadoc)
	 * @see java.util.LinkedList#offerLast(java.lang.Object)
	 */
	@Override
	public boolean offerLast(T listener)
	{
		this.addLast(listener);
		return true;
	}
	
	/* (non-Javadoc)
	 * @see java.util.LinkedList#add(int, java.lang.Object)
	 */
	@Override
	public void add(int index, T listener)
	{
		if (!this.contains(listener))
		{
			super.add(index, listener);
			this.invalidate();
		}
	}
	
	/* (non-Javadoc)
	 * @see java.util.LinkedList#addFirst(java.lang.Object)
	 */
	@Override
	public void addFirst(T listener)
	{
		if (!this.contains(listener))
		{
			super.addFirst(listener);
			this.invalidate();
		}
	}
	
	/* (non-Javadoc)
	 * @see java.util.LinkedList#addLast(java.lang.Object)
	 */
	@Override
	public void addLast(T listener)
	{
		if (!this.contains(listener))
		{
			super.addLast(listener);
			this.invalidate();
		}
	}

	/* (non-Javadoc)
	 * @see java.util.LinkedList#addAll(java.util.Collection)
	 */
	@Override
	public boolean addAll(Collection<? extends T> listeners)
	{
		for (T listener : listeners)
		{
			if (!this.contains(listener))
			{
				super.add(listener);
			}
		}
		
		this.invalidate();
		return true;
	}
	
	/* (non-Javadoc)
	 * @see java.util.LinkedList#addAll(int, java.util.Collection)
	 */
	@Override
	public boolean addAll(int index, Collection<? extends T> listeners)
	{
		throw new UnsupportedOperationException("'addAll' is not supported for HandlerList");
	}
	
	/* (non-Javadoc)
	 * @see java.util.LinkedList#remove()
	 */
	@Override
	public T remove()
	{
		return this.removeFirst();
	}
	
	/* (non-Javadoc)
	 * @see java.util.LinkedList#remove(int)
	 */
	@Override
	public T remove(int index)
	{
		T removed = super.remove(index);
		this.invalidate();
		return removed;
	}
	
	/* (non-Javadoc)
	 * @see java.util.LinkedList#remove(java.lang.Object)
	 */
	@Override
	public boolean remove(Object listener)
	{
		boolean removed = super.remove(listener);
		this.invalidate();
		return removed;
	}
	
	/* (non-Javadoc)
	 * @see java.util.LinkedList#removeFirst()
	 */
	@Override
	public T removeFirst()
	{
		T removed = super.removeFirst();
		this.invalidate();
		return removed;
	}
	
	/* (non-Javadoc)
	 * @see java.util.LinkedList#removeFirstOccurrence(java.lang.Object)
	 */
	@Override
	public boolean removeFirstOccurrence(Object listener)
	{
		return this.remove(listener);
	}
	
	/* (non-Javadoc)
	 * @see java.util.LinkedList#removeLast()
	 */
	@Override
	public T removeLast()
	{
		T removed = super.removeLast();
		this.invalidate();
		return removed;
	}
	
	/* (non-Javadoc)
	 * @see java.util.LinkedList#removeLastOccurrence(java.lang.Object)
	 */
	@Override
	public boolean removeLastOccurrence(Object listener)
	{
		boolean removed = super.removeLastOccurrence(listener);
		this.invalidate();
		return removed;
	}
	
	/* (non-Javadoc)
	 * @see java.util.AbstractCollection#removeAll(java.util.Collection)
	 */
	@Override
	public boolean removeAll(Collection<?> listeners)
	{
		boolean removed = super.removeAll(listeners);
		this.invalidate();
		return removed;
	}
	
	/* (non-Javadoc)
	 * @see java.util.LinkedList#poll()
	 */
	@Override
	public T poll()
	{
		T polled = super.poll();
		this.invalidate();
		return polled;
	}
	
	/* (non-Javadoc)
	 * @see java.util.LinkedList#pollFirst()
	 */
	@Override
	public T pollFirst()
	{
		T polled = super.pollFirst();
		this.invalidate();
		return polled;
	}
	
	/* (non-Javadoc)
	 * @see java.util.LinkedList#pollLast()
	 */
	@Override
	public T pollLast()
	{
		T polled = super.pollLast();
		this.invalidate();
		return polled;
	}
	
	/* (non-Javadoc)
	 * @see java.util.LinkedList#push(java.lang.Object)
	 */
	@Override
	public void push(T listener)
	{
		this.addFirst(listener);
	}
	
	/* (non-Javadoc)
	 * @see java.util.LinkedList#pop()
	 */
	@Override
	public T pop()
	{
		return this.removeFirst();
	}
	
	/* (non-Javadoc)
	 * @see java.util.LinkedList#set(int, java.lang.Object)
	 */
	@Override
	public T set(int index, T listener)
	{
		T oldValue = null;
		
		if (!this.contains(listener))
		{
			oldValue = super.set(index, listener);
			this.invalidate();
		}
		
		return oldValue;
	}
	
	/**
	 * Base class for baked handler lists 
	 * 
	 * @author Adam Mummery-Smith
	 *
	 * @param <T>
	 */
	public static abstract class BakedHandlerList<T>
	{
		public abstract T get();

		public abstract BakedHandlerList<T> populate(List<T> listeners);
	}
	
	/**
	 * Exception to throw when failing to bake a handler list
	 * 
	 * @author Adam Mummery-Smith
	 */
	static class BakingFailedException extends RuntimeException
	{
		private static final long serialVersionUID = 1L;

		public BakingFailedException(Throwable cause)
		{
			super("An unexpected error occurred while baking the handler list", cause);
		}
	}
	
	/**
	 * ClassLoader which generates the baked handler list
	 * 
	 * @author Adam Mummery-Smith
	 * @param <T>
	 */
	static class HandlerListClassLoader<T> extends URLClassLoader
	{
		private static final String HANDLER_VAR_PREFIX = "handler$";

		/**
		 * Unique index number, just to ensure no name clashes
		 */
		private static int handlerIndex;

		/**
		 * Interface type which this classloader is generating handler for 
		 */
		private final Class<T> type;
		
		/**
		 * Calculated class ref for the class type so that we don't have to keep calling getName().replace('.', '/')
		 */
		private final String typeRef;
		
		/**
		 * Logic operation to apply when running a callback with a boolean 
		 */
		private final ReturnLogicOp logicOp;
		
		/**
		 * Size of the handler list
		 */
		private int size;

		/**
		 * @param type
		 * @param size
		 */
		HandlerListClassLoader(Class<T> type, ReturnLogicOp logicOp)
		{
			super(new URL[0], Launch.classLoader);
			this.type = type;
			this.typeRef = type.getName().replace('.', '/');
			this.logicOp = logicOp;
		}
		
		/**
		 * Create and return a new baked handler list
		 */
		@SuppressWarnings("unchecked")
		public BakedHandlerList<T> newHandler(HandlerList<T> list)
		{
			this.size = list.size();
			
			Class<BakedHandlerList<T>> handlerClass = null;

			try
			{
				// Inflect the class name and attempt to generate the class
				String className = HandlerListClassLoader.getNextClassName(Obf.HandlerList.name, this.type.getSimpleName());
				handlerClass = (Class<BakedHandlerList<T>>)this.loadClass(className);
			}
			catch (ClassNotFoundException ex)
			{
				throw new BakingFailedException(ex);
			}

			try
			{
				// Create an instance of the class, populate the entries from the supplied list and return it
				BakedHandlerList<T> handlerList = this.createInstance(handlerClass);
				return handlerList.populate(list);
			}
			catch (InstantiationException ex)
			{
				throw new BakingFailedException(ex);
			}
		}

		/**
		 * Create an instance of the baked class
		 * 
		 * @param handlerClass Baked HandlerList class
		 * @return new instance of the Baked HandlerList class 
		 * @throws InstantiationException if the handler can't be created for some reason
		 */
		private BakedHandlerList<T> createInstance(Class<BakedHandlerList<T>> handlerClass) throws InstantiationException
		{
			try
			{
				Constructor<BakedHandlerList<T>> ctor = handlerClass.getDeclaredConstructor();
				ctor.setAccessible(true);
				return ctor.newInstance();
			}
			catch (Exception ex)
			{
				InstantiationException ie = new InstantiationException("Error instantiating class " + handlerClass);
				ie.setStackTrace(ex.getStackTrace());
				throw ie;
			}
		}

		/* (non-Javadoc)
		 * @see java.net.URLClassLoader#findClass(java.lang.String)
		 */
		@Override
		protected Class<?> findClass(String name) throws ClassNotFoundException
		{
			try
			{
				// Read the basic class template
				byte[] bytes = Launch.classLoader.getClassBytes(Obf.BakedHandlerList.name);
				ClassReader classReader = new ClassReader(bytes);
				ClassNode classNode = new ClassNode();
				classReader.accept(classNode, ClassReader.EXPAND_FRAMES);
				
				// Apply all transformations to the class, injects our custom code 
				this.transform(name, classNode);
				
				// Write the class
				ClassWriter classWriter = new ClassWriter(classReader, ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
				classNode.accept(classWriter);
				bytes = classWriter.toByteArray();
				
//				classNode.accept(new org.objectweb.asm.util.CheckClassAdapter(new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES)));
				
				// Delegate to ClassLoader's usual behaviour to load the class we just generated
				return this.defineClass(name, bytes, 0, bytes.length);
			}
			catch (Throwable th)
			{
				th.printStackTrace();
				return null;
			}
		}

		/**
		 * Perform all class bytecode transformations
		 * 
		 * @param name
		 * @param classNode
		 * @throws IOException 
		 */
		private void transform(String name, ClassNode classNode) throws IOException
		{
			LiteLoaderLogger.info("Baking listener list for %s with %d listeners", this.type.getSimpleName(), this.size);
			LiteLoaderLogger.debug("Generating: %s", name);
			
			this.populateClass(name, classNode);
			this.transformMethods(name, classNode);
			this.injectInterfaceMethods(classNode, this.type.getName());
		}

		/**
		 * Populate the class node itself
		 * 
		 * @param name
		 * @param classNode
		 */
		private void populateClass(String name, ClassNode classNode)
		{
			classNode.access = classNode.access & ~Opcodes.ACC_ABSTRACT;
			classNode.name = name.replace('.', '/');
			classNode.superName = Obf.BakedHandlerList.ref;
			classNode.interfaces.add(this.typeRef);
			classNode.sourceFile = name.substring(name.lastIndexOf('.') + 1) + ".java";

			for (int handlerIndex = 0; handlerIndex < this.size; handlerIndex++)
			{
				classNode.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, HandlerListClassLoader.HANDLER_VAR_PREFIX + handlerIndex, "L" + this.typeRef + ";", null, null));
			}
		}

		/**
		 * Transform existing methods in the template class
		 * 
		 * @param name
		 * @param classNode
		 */
		private void transformMethods(String name, ClassNode classNode)
		{
			for (Iterator<MethodNode> methodIterator = classNode.methods.iterator(); methodIterator.hasNext();)
			{
				MethodNode method = methodIterator.next();
				if (Obf.constructor.name.equals(method.name))
				{
					this.processCtor(classNode, method);
				}
				else if ("get".equals(method.name))
				{
					this.processGet(classNode, method);
				}
				else if ("populate".equals(method.name))
				{
					this.processPopulate(classNode, method);
				}
			}
		}
		
		/**
		 * Transform the constructor
		 * 
		 * @param classNode
		 * @param method
		 */
		private void processCtor(ClassNode classNode, MethodNode method)
		{
			for (Iterator<AbstractInsnNode> iter = method.instructions.iterator(); iter.hasNext();)
			{
				AbstractInsnNode insn = iter.next();
				if (insn instanceof MethodInsnNode)
				{
					MethodInsnNode methodInsn = (MethodInsnNode)insn;
					if (methodInsn.owner.equals("java/lang/Object"))
					{
						methodInsn.owner = Obf.BakedHandlerList.ref;
					}
				}
			}
		}

		/**
		 * Transform .get()
		 * 
		 * @param classNode
		 * @param method
		 */
		private void processGet(ClassNode classNode, MethodNode method)
		{
			method.access = method.access & ~Opcodes.ACC_ABSTRACT;
			method.instructions.clear();

			method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
			method.instructions.add(new InsnNode(Opcodes.ARETURN));
			
			method.maxStack = 1;
			method.maxLocals = 1;
		}

		/**
		 * Transform .processPopulate()
		 * 
		 * @param classNode
		 * @param method
		 */
		private void processPopulate(ClassNode classNode, MethodNode method)
		{
			method.access = method.access & ~Opcodes.ACC_ABSTRACT;
			method.instructions.clear();
			
			for (int handlerIndex = 0; handlerIndex < this.size; handlerIndex++)
			{
				method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
				method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
				method.instructions.add(handlerIndex > Short.MAX_VALUE ? new LdcInsnNode(new Integer(handlerIndex)) : new IntInsnNode(Opcodes.SIPUSH, handlerIndex));
				method.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "get", "(I)Ljava/lang/Object;", true));
				method.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, this.typeRef));
				method.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, classNode.name, HandlerListClassLoader.HANDLER_VAR_PREFIX + handlerIndex, "L" + this.typeRef + ";"));
			}		

			method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
			method.instructions.add(new InsnNode(Opcodes.ARETURN));
			
			method.maxStack = 3;
			method.maxLocals = 2;
		}

		/**
		 * Recurse down the interface inheritance hierarchy and inject methods to handle each interface
		 * 
		 * @param classNode
		 * @param interfaceName
		 * @throws IOException
		 */
		private void injectInterfaceMethods(ClassNode classNode, String interfaceName) throws IOException
		{
			ClassReader interfaceReader = new ClassReader(HandlerListClassLoader.getInterfaceBytes(interfaceName));
			ClassNode interfaceNode = new ClassNode();
			interfaceReader.accept(interfaceNode, 0);
			
			for (MethodNode interfaceMethod : interfaceNode.methods)
			{
				classNode.methods.add(interfaceMethod);
				this.populateInterfaceMethod(classNode, interfaceMethod);
			}
			
			for (String parentInterface : interfaceNode.interfaces)
			{
				this.injectInterfaceMethods(classNode, parentInterface.replace('/', '.'));
			}
		}

		/**
		 * Inject the supplied interface method into the target class an populate it with method calls to the list members 
		 * 
		 * @param classNode
		 * @param method
		 */
		private void populateInterfaceMethod(ClassNode classNode, MethodNode method)
		{
			Type returnType = Type.getReturnType(method.desc);
			Type[] args = Type.getArgumentTypes(method.desc);
			
			if (returnType.equals(Type.BOOLEAN_TYPE))
			{
				method.access = Opcodes.ACC_PUBLIC;
				this.populateBooleanInvokationChain(classNode, method, args);
			}
			else
			{
				method.access = Opcodes.ACC_PUBLIC;
				this.populateVoidInvokationChain(classNode, method, args, returnType);
			}
		}

		/**
		 * @param classNode
		 * @param method
		 * @param args
		 */
		private void populateVoidInvokationChain(ClassNode classNode, MethodNode method, Type[] args, Type returnType)
		{
			int returnSize = returnType.getSize();
			for (int handlerIndex = 0; handlerIndex < this.size; handlerIndex++)
			{
				this.invokeHandler(handlerIndex, classNode, method, args);
				if (returnSize > 0)
				{
					method.instructions.add(new InsnNode(returnSize == 1 ? Opcodes.POP : Opcodes.POP2));
				}
			}
			
			if (returnSize > 0)
			{
				if (returnType.getSort() == Type.OBJECT)
				{
					method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
				}
				else if (returnSize == 1)
				{
					method.instructions.add(new InsnNode(Opcodes.ICONST_0));
				}
				else if (returnSize == 2)
				{
					method.instructions.add(new InsnNode(Opcodes.DCONST_0));
				}
			}
			
			method.instructions.add(new InsnNode(returnType.getOpcode(Opcodes.IRETURN)));

			method.maxLocals = args.length + 1;
			method.maxStack = args.length + 1;
		}

		/**
		 * @param classNode
		 * @param method
		 * @param args
		 */
		private void populateBooleanInvokationChain(ClassNode classNode, MethodNode method, Type[] args)
		{
			boolean isOrOperation = this.logicOp.isOr();
			boolean breakOnMatch = this.logicOp.breakOnMatch();
			int initialValue = isOrOperation && (!this.logicOp.assumeTrue() || this.size > 0) ? Opcodes.ICONST_0 : Opcodes.ICONST_1;
			int localIndex = this.getFirstLocalIndex(args);
			
			method.instructions.add(new InsnNode(initialValue));
			method.instructions.add(new VarInsnNode(Opcodes.ISTORE, localIndex));
			
			for (int handlerIndex = 0; handlerIndex < this.size; handlerIndex++)
			{
				this.invokeHandler(handlerIndex, classNode, method, args); // invoke the method, this will leave the return value on the stack
				
				int jumpCondition = isOrOperation ? Opcodes.IFEQ : Opcodes.IFNE;     // jump if zero for OR, jump if one for AND
				int semaphore = isOrOperation ? Opcodes.ICONST_1 : Opcodes.ICONST_0; // will push TRUE for OR, will push FALSE for AND
				
				LabelNode lbl = new LabelNode();
				method.instructions.add(new JumpInsnNode(jumpCondition, lbl)); // jump over the set/return based on the condition
				method.instructions.add(new InsnNode(semaphore)); // push TRUE or FALSE onto the stack
				method.instructions.add(breakOnMatch ? new InsnNode(Opcodes.IRETURN) : new VarInsnNode(Opcodes.ISTORE, localIndex)); // set local or return
				method.instructions.add(lbl); // jump here
			}		
			
			method.instructions.add(new VarInsnNode(Opcodes.ILOAD, localIndex));
			method.instructions.add(new InsnNode(Opcodes.IRETURN));
			
			method.maxLocals = args.length + 2;
			method.maxStack = args.length + 1;
		}

		/**
		 * @param handlerIndex
		 * @param classNode
		 * @param method
		 * @param args
		 */
		private int invokeHandler(int handlerIndex, ClassNode classNode, MethodNode method, Type[] args)
		{
			LabelNode lineNumberLabel = new LabelNode(new Label());
			method.instructions.add(lineNumberLabel);
			method.instructions.add(new LineNumberNode(100 + handlerIndex, lineNumberLabel));
			method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
			method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, classNode.name, HandlerListClassLoader.HANDLER_VAR_PREFIX + handlerIndex, "L" + this.typeRef + ";"));
			return this.invokeInterfaceMethod(method, args);
		}

		/**
		 * Inject instructions into the supplied method to invoke the same method on the supplied interface 
		 * 
		 * @param method
		 * @param args
		 */
		private int invokeInterfaceMethod(MethodNode method, Type[] args)
		{
			int argNumber = 1;
			for (Type type : args)
			{
				method.instructions.add(new VarInsnNode(type.getOpcode(Opcodes.ILOAD), argNumber));
				argNumber += type.getSize();
			}
			
			method.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, this.typeRef, method.name, method.desc, true));
			return argNumber;
		}
		
		private int getFirstLocalIndex(Type[] args)
		{
			int argNumber = 1;
			for (Type type : args) argNumber += type.getSize();
			return argNumber;
		}

		/**
		 * @param baseName
		 * @param typeName
		 * @return
		 */
		private static String getNextClassName(String baseName, String typeName)
		{
			return String.format("%s$%s%d", baseName, typeName, HandlerListClassLoader.handlerIndex++);
		}

		/**
		 * @param name
		 * @return
		 * @throws IOException
		 */
		private static byte[] getInterfaceBytes(String name) throws IOException
		{
			byte[] bytes = Launch.classLoader.getClassBytes(name);

			for (final IClassTransformer transformer : Launch.classLoader.getTransformers())
			{
				bytes = transformer.transform(name, name, bytes);
			}
			
			return bytes;
		}
	}
}
