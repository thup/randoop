package randoop.generation;

import org.checkerframework.checker.regex.qual.Var;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import randoop.BugInRandoopException;
import randoop.main.GenInputsAbstract;
import randoop.operation.ConstructorCall;
import randoop.operation.MethodCall;
import randoop.operation.TypedOperation;
import randoop.sequence.Sequence;
import randoop.sequence.Variable;
import randoop.types.ArrayType;
import randoop.types.ConcreteTypes;
import randoop.types.GeneralType;
import randoop.types.GenericClassType;
import randoop.types.JDKTypes;
import randoop.types.ParameterizedType;
import randoop.types.ReferenceArgument;
import randoop.types.ReferenceType;
import randoop.types.Substitution;
import randoop.types.TypeArgument;
import randoop.types.TypeTuple;
import randoop.util.ArrayListSimpleList;
import randoop.util.Randomness;
import randoop.types.Match;
import randoop.util.SimpleList;

public class HelperSequenceCreator {

  /**
   * Returns a sequence that creates an object of type compatible with the given
   * class. Wraps the object in a list, and returns the list.
   *
   * CURRENTLY, will return a sequence (i.e. a non-empty list) only if cls is an
   * array.
   *
   * @param components  the component manager with existing sequences
   * @param collectionType  the query type
   * @return the singleton list containing the compatible sequence
   */
  public static SimpleList<Sequence> createArraySequence(ComponentManager components, GeneralType collectionType) {

    if (!collectionType.isArray()) {
      return new ArrayListSimpleList<Sequence>();
    }

    ArrayType arrayType = (ArrayType)collectionType;
    GeneralType elementType = arrayType.getElementType();

    Sequence s = null;

    if (elementType.isPrimitive()) {
      s = randPrimitiveArray(elementType);
    } else {
      SimpleList<Sequence> candidates =
          components.getSequencesForType(elementType, false);
      if (candidates.isEmpty()) {
        // No sequences that produce appropriate component values found, and
        if (GenInputsAbstract.forbid_null) {
          // use of null is forbidden. So, return the empty array.
          s = new Sequence().extend(TypedOperation.createArrayCreation(arrayType, 0));
        } else {
          // null is allowed.
          s = new Sequence();
          List<Variable> ins = new ArrayList<>();
          TypedOperation declOp;
          if (Randomness.weighedCoinFlip(0.5)) {
            declOp = TypedOperation.createArrayCreation(arrayType, 0);
          } else {
            s = s.extend(TypedOperation.createNullOrZeroInitializationForType(elementType));
            ins.add(s.getVariable(0));
            declOp = TypedOperation.createArrayCreation(arrayType, 1);
          }
          s = s.extend(declOp, ins);
        }
      } else {
        // Return the array [ x ] where x is the last value in the sequence.
        TypedOperation declOp = TypedOperation.createArrayCreation(arrayType, 1);
        s = candidates.get(Randomness.nextRandomInt(candidates.size()));
        List<Variable> ins = new ArrayList<>();
        // XXX IS THIS OLD COMMENT TRUE? : this assumes that last statement will
        // have such a var,
        // which I know is currently true because of SequenceCollection
        // implementation.
        ins.add(s.randomVariableForTypeLastStatement(elementType));
        s = s.extend(declOp, ins);
      }
    }
    assert s != null;
    ArrayListSimpleList<Sequence> l = new ArrayListSimpleList<>();
    l.add(s);
    return l;
  }

  // XXX component manager has a sequences doesn't it?
  private static Sequence randPrimitiveArray(GeneralType componentType) {
    assert componentType.isPrimitive();
    Set<Object> potentialElts = SeedSequences.getSeeds(componentType);
    int length = Randomness.nextRandomInt(4);
    Sequence s = new Sequence();
    List<Variable> emptylist = new ArrayList<>();
    for (int i = 0; i < length; i++) {
      Object elt = Randomness.randomSetMember(potentialElts);
      s = s.extend(TypedOperation.createPrimitiveInitialization(componentType, elt), emptylist);
    }
    List<Variable> inputs = new ArrayList<>();
    for (int i = 0; i < length; i++) {
      inputs.add(s.getVariable(i));
    }
    s = s.extend(TypedOperation.createArrayCreation(ArrayType.ofElementType(componentType), length), inputs);
    return s;
  }

  /**
   * strategy for creating a collection C with element type E
   * - build an array of type E[]
   * - choose implementing class D
   * - create an empty collection of type D with element E
   * - call Collection.addAll(collection, array)
   *
   * @param componentManager  the component manager
   * @param inputType  the collection type to build
   * @return a sequence that creates a collection of the given type
   */
  public static Sequence createCollection(ComponentManager componentManager, GeneralType inputType) {

    if (!inputType.isParameterized()) {
      throw new IllegalArgumentException("type must be parameterized");
    }
    assert ! inputType.isGeneric() : "type must be instantiated";

    // get the element type
    ParameterizedType collectionType = (ParameterizedType)inputType;
    List<TypeArgument> argumentList = collectionType.getTypeArguments();
    assert argumentList.size() == 1 : "Collection classes should have one type argument";
    ReferenceType elementType = ((ReferenceArgument)argumentList.get(0)).getReferenceType();

    int totStatements = 0;
    List<Sequence> inputSequences = new ArrayList<>();
    List<Integer> variableIndices = new ArrayList<>();

    // build sequence to create array of element type
    SimpleList<Sequence> candidates = componentManager.getSequencesForType(elementType, false);
    int length = Randomness.nextRandomInt(candidates.size()) + 1;
    Sequence inputSequence = createAnArray(candidates, elementType, length);
    inputSequences.add(inputSequence);
    variableIndices.add(inputSequence.getLastVariable().index);
    totStatements += inputSequence.size();

    // select implementing Collection type and instantiate
    GenericClassType implementingType = JDKTypes.getImplementingType(collectionType);
    ParameterizedType creationType = implementingType.instantiate(elementType);

    // build sequence to create an Collection object
    TypedOperation creationOperation = getCollectionConstructor(creationType);
    Sequence creationSequence = new Sequence();
    creationSequence = creationSequence.extend(creationOperation, new ArrayList<Variable>());
    inputSequences.add(creationSequence);
    variableIndices.add(totStatements + creationSequence.getLastVariable().index);
    totStatements += creationSequence.size();

    // call Collections.addAll(c, inputArray)
    TypedOperation addOperation = getCollectionAddAllOperation(elementType);

    Sequence helperSequence = Sequence.concatenate(inputSequences);
    List<Variable> inputs = new ArrayList<>();
    for (int index : variableIndices) {
      inputs.add(helperSequence.getVariable(index));
    }
    return helperSequence.extend(addOperation, inputs);
  }

  /**
   * Creates a sequence that builds an array of the given element type using sequences from the
   * given list of candidates.
   *
   * @param candidates  the list of candidate elements
   * @param elementType  the type of elements for the array
   * @return a sequence that creates an array with the given element type
   */
  private static Sequence createAnArray(SimpleList<Sequence> candidates, ReferenceType elementType, int length) {
    int totStatements = 0;
    List<Sequence> inputSequences = new ArrayList<>();
    List<Integer> variables = new ArrayList<>();
    for (int i = 0; i < length; i++) {
      Sequence inputSeq = candidates.get(Randomness.nextRandomInt(candidates.size()));
      Variable inputVar = inputSeq.randomVariableForTypeLastStatement(elementType);

      assert inputVar != null;
      variables.add(totStatements + inputVar.index);
      inputSequences.add(inputSeq);
      totStatements += inputSeq.size();
    }
    Sequence inputSequence = Sequence.concatenate(inputSequences);
    List<Variable> inputs = new ArrayList<>();
    for (Integer inputIndex : variables) {
      inputs.add(inputSequence.getVariable(inputIndex));
    }

    return inputSequence.extend(TypedOperation.createArrayCreation(ArrayType.ofElementType(elementType), length), inputs);
  }

  /**
   * Creates a {@link TypedOperation} for the default constructor for the given {@code java.util.Collection}
   * type.
   *
   * @param creationType  the collection type
   * @return an operation that calls the default constructor of the given collection type
   */
  private static TypedOperation getCollectionConstructor(ParameterizedType creationType) {
    Constructor<?> constructor = null;
    try {
      constructor = creationType.getRuntimeClass().getConstructor();
    } catch (NoSuchMethodException e) {
      throw new BugInRandoopException("Can't find default constructor for Collection " + creationType + ": " + e.getMessage());
    }
    ConstructorCall op = new ConstructorCall(constructor);

    return new TypedOperation(op, creationType, new TypeTuple(), creationType);
  }

  public static TypedOperation getCollectionAddAllOperation(ReferenceType elementType) {
    Class<?> collectionsClass = Collections.class;
    Method method = null;
    try {
      method = collectionsClass.getMethod("addAll", JDKTypes.COLLECTION_TYPE.getRuntimeClass(), (new Object[]{}).getClass());
    } catch (NoSuchMethodException e) {
      throw new BugInRandoopException("Can't find Collections.addAll method: " + e.getMessage());
    }
    assert method.getTypeParameters().length == 1: "method should have one type parameter";
    List<TypeArgument> argList = new ArrayList<>();
    argList.add(TypeArgument.forType(method.getTypeParameters()[0]));
    List<GeneralType> elementTypeList = new ArrayList<>();
    elementTypeList.add(elementType);
    Substitution substitution = Substitution.forArgs(argList, elementTypeList);
    MethodCall op = new MethodCall(method);
    List<GeneralType> paramTypes = new ArrayList<>();
    for (Type param : method.getGenericParameterTypes()) {
      paramTypes.add(GeneralType.forType(param).apply(substitution));
    }
    TypeTuple inputTypes = new TypeTuple(paramTypes);
    return new TypedOperation(op, GeneralType.forClass(collectionsClass), inputTypes, ConcreteTypes.BOOLEAN_TYPE);
  }
}