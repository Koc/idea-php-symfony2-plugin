package fr.adrienbrault.idea.symfony2plugin.form.util;

import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.openapi.project.Project;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.containers.ContainerUtil;
import com.jetbrains.php.PhpIndex;
import com.jetbrains.php.lang.psi.elements.*;
import fr.adrienbrault.idea.symfony2plugin.Symfony2Icons;
import fr.adrienbrault.idea.symfony2plugin.form.dict.*;
import fr.adrienbrault.idea.symfony2plugin.form.visitor.FormOptionLookupVisitor;
import fr.adrienbrault.idea.symfony2plugin.form.visitor.FormOptionVisitor;
import fr.adrienbrault.idea.symfony2plugin.util.PhpElementsUtil;
import fr.adrienbrault.idea.symfony2plugin.util.PsiElementUtils;
import fr.adrienbrault.idea.symfony2plugin.util.service.ServiceXmlParserFactory;
import org.apache.commons.lang.StringUtils;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * @author Daniel Espendiller <daniel@espendiller.net>
 */
public class FormOptionsUtil {

    public static final String EXTENDED_TYPE_METHOD = "getExtendedType";
    public static final String[] FORM_OPTION_METHODS = new String[]{"setDefaultOptions", "configureOptions"};

    /**
     * Find form extensions extends given form type
     *
     * @param formTypeNames Fqn class "Foo\Foo", "\Foo\Foo" or string value "foo";
     *                      should have all parents a FormType can have
     */
    @NotNull
    public static Collection<FormClass> getExtendedTypeClasses(@NotNull Project project, @NotNull String... formTypeNames) {

        // strip "\"
        List<String> formTypeNamesList = ContainerUtil.map(Arrays.asList(formTypeNames), s -> {
            return StringUtils.stripStart(s, "\\");
        });

        Collection<FormClass> extendedTypeClasses = new ArrayList<>();
        for(PhpClass phpClass: getFormTypeExtensionClassNames(project)) {
            String formExtendedType = FormUtil.getFormExtendedType(phpClass);
            if(formExtendedType != null && formTypeNamesList.contains(formExtendedType)) {
                extendedTypeClasses.add(new FormClass(FormClassEnum.EXTENSION, phpClass, true));
            }
        }

        return extendedTypeClasses;
    }

    @NotNull
    private static Set<PhpClass> getFormTypeExtensionClassNames(@NotNull Project project) {

        Set<PhpClass> phpClasses = new HashSet<>();

        // @TODO: should be same as interface?
        for (String s : ServiceXmlParserFactory.getInstance(project, FormExtensionServiceParser.class).getFormExtensions().keySet()) {
            ContainerUtil.addIfNotNull(
                phpClasses,
                PhpElementsUtil.getClass(project, s)
            );
        }

        for(PhpClass phpClass: PhpIndex.getInstance(project).getAllSubclasses(FormUtil.FORM_EXTENSION_INTERFACE)) {
            if(!FormUtil.isValidFormPhpClass(phpClass)) {
                continue;
            }

            phpClasses.add(phpClass);
        }

        return phpClasses;
    }

    @NotNull
    public static Map<String, FormOption> getFormExtensionKeys(@NotNull Project project, @NotNull String... formTypeNames) {

        Collection<FormClass> typeClasses = FormOptionsUtil.getExtendedTypeClasses(project, formTypeNames);
        Map<String, FormOption> extensionClassMap = new HashMap<>();

        for(FormClass extensionClass: typeClasses) {
            extensionClassMap.putAll(getDefaultOptions(project, extensionClass.getPhpClass(), extensionClass));
        }

        return extensionClassMap;
    }

    /**
     * finishView, buildView:
     * $this->vars
     */
    public static Set<String> getFormViewVars(Project project, String... formTypeNames) {

        Set<String> stringSet = new HashSet<>();

        Set<String> uniqueClass = new HashSet<>();
        List<PhpClass> phpClasses = new ArrayList<>();

        // attach core form phpclass
        // @TODO: add formtype itself
        PhpClass coreForm = FormUtil.getFormTypeToClass(project, "form");
        if(coreForm != null) {
            phpClasses.add(coreForm);
            uniqueClass.add(coreForm.getPresentableFQN());
        }

        // for extension can also provide vars
        for(FormOption entry: FormOptionsUtil.getFormExtensionKeys(project, formTypeNames).values()) {
            PhpClass phpClass = entry.getFormClass().getPhpClass();
            if(!uniqueClass.contains(phpClass.getPresentableFQN())) {
                phpClasses.add(phpClass);
            }
        }

        for(PhpClass phpClass: phpClasses) {
            for(String stringMethod: new String[] {"finishView", "buildView"} ) {
                Method method = phpClass.findMethodByName(stringMethod);
                if(method != null) {

                    // self method
                    getMethodVars(stringSet, method);

                    // allow parent::
                    // @TODO: provide global util method
                    for(ClassReference classReference : PsiTreeUtil.collectElementsOfType(method, ClassReference.class)) {
                        if("parent".equals(classReference.getName())) {
                            PsiElement methodReference = classReference.getContext();
                            if(methodReference instanceof MethodReference) {
                                PsiElement parentMethod = ((MethodReference) methodReference).resolve();
                                if(parentMethod instanceof Method) {
                                    getMethodVars(stringSet, (Method) parentMethod);
                                }
                            }

                        }

                    }
                }
            }

        }

        return stringSet;
    }

    private static void getMethodVars(Set<String> stringSet, Method method) {
        Collection<FieldReference> fieldReferences = PsiTreeUtil.collectElementsOfType(method, FieldReference.class);
        for(FieldReference fieldReference: fieldReferences) {
            PsiElement psiVar = PsiElementUtils.getChildrenOfType(fieldReference, PlatformPatterns.psiElement().withText("vars"));
            if(psiVar != null) {
                getFormViewVarsAttachKeys(stringSet, fieldReference);
            }

        }
    }

    /**
     * $this->vars['test']
     * $view->vars = array_replace($view->vars, array(...));
     */
    private static void getFormViewVarsAttachKeys(Set<String> stringSet, FieldReference fieldReference) {

        // $this->vars['test']
        PsiElement context = fieldReference.getContext();
        if(context instanceof ArrayAccessExpression) {
            ArrayIndex arrayIndex = PsiTreeUtil.findChildOfType(context, ArrayIndex.class);
            if(arrayIndex != null) {
                PsiElement psiElement = arrayIndex.getFirstChild();
                if(psiElement instanceof StringLiteralExpression) {
                    String contents = ((StringLiteralExpression) psiElement).getContents();
                    if(StringUtils.isNotBlank(contents)) {
                        stringSet.add(contents);
                    }
                }
            }
        }

        // array_replace($view->vars, array(...))
        if(context instanceof ParameterList) {
            PsiElement functionReference = context.getContext();
            if(functionReference instanceof FunctionReference && "array_replace".equals(((FunctionReference) functionReference).getName())) {
                PsiElement[] psiElements = ((ParameterList) context).getParameters();
                if(psiElements.length > 1) {
                    if(psiElements[1] instanceof ArrayCreationExpression) {
                        stringSet.addAll(PhpElementsUtil.getArrayCreationKeys((ArrayCreationExpression) psiElements[1]));
                    }
                }
            }
        }
    }


    @Deprecated
    public static Map<String, String> getFormDefaultKeys(@NotNull Project project, @NotNull String formTypeName) {
        final Map<String, String> items = new HashMap<>();

        getFormDefaultKeys(project, formTypeName, new HashMap<>(), new FormUtil.FormTypeCollector(project).collect(), 0, (psiElement, option, formClass, optionEnum) -> {
            String presentableFQN = formClass.getPhpClass().getPresentableFQN();
            items.put(option, presentableFQN);
        });

        return items;
    }

    public static void visitFormOptions(@NotNull Project project, @NotNull String formTypeName, @NotNull FormOptionVisitor visitor) {
        visitFormOptions(project, formTypeName, new HashMap<>(), new FormUtil.FormTypeCollector(project).collect(), 0, visitor);
    }

    private static Map<String, String> visitFormOptions(Project project, String formTypeName, HashMap<String, String> defaultValues, FormUtil.FormTypeCollector collector, int depth, @NotNull FormOptionVisitor visitor) {

        PhpClass phpClass = collector.getFormTypeToClass(formTypeName);
        if(phpClass == null) {
            return defaultValues;
        }

        getDefaultOptions(project, phpClass, new FormClass(FormClassEnum.FORM_TYPE, phpClass, false), visitor);
        for (FormClass formClass : getExtendedTypeClasses(project, formTypeName)) {
            getDefaultOptions(project, formClass.getPhpClass(), new FormClass(FormClassEnum.EXTENSION, formClass.getPhpClass(), false), visitor);
        }

        // recursive search for parent form types
        if (depth < 10) {
            String formParent = FormUtil.getFormParentOfPhpClass(phpClass);
            if(formParent != null) {
                visitFormOptions(project, formParent, defaultValues, collector, ++depth, visitor);
            }
        }

        return defaultValues;
    }

    public static void getFormDefaultKeys(@NotNull Project project, @NotNull String formTypeName, @NotNull FormOptionVisitor visitor) {
        getFormDefaultKeys(project, formTypeName, new HashMap<>(), new FormUtil.FormTypeCollector(project).collect(), 0, visitor);
    }

    private static Map<String, String> getFormDefaultKeys(Project project, String formTypeName, HashMap<String, String> defaultValues, FormUtil.FormTypeCollector collector, int depth, @NotNull FormOptionVisitor visitor) {

        PhpClass phpClass = collector.getFormTypeToClass(formTypeName);
        if(phpClass == null) {
            return defaultValues;
        }

        getDefaultOptions(project, phpClass, new FormClass(FormClassEnum.FORM_TYPE, phpClass, false), visitor);

        // recursive search for parent form types
        if (depth < 10) {
            String formParent = FormUtil.getFormParentOfPhpClass(phpClass);
            if(formParent != null) {
                getFormDefaultKeys(project, formParent, defaultValues, collector, ++depth, visitor);
            }
        }

        return defaultValues;
    }

    @NotNull
    private static Map<String, FormOption> getDefaultOptions(@NotNull Project project, @NotNull PhpClass phpClass, @NotNull FormClass formClass) {
        final Map<String, FormOption> options = new HashMap<>();

        getDefaultOptions(project, phpClass, formClass, (psiElement, option, formClass1, optionEnum) -> {
            // append REQUIRED, if we already know this value
            if(options.containsKey(option)) {
                options.get(option).addOptionEnum(optionEnum);
            } else {
                options.put(option, new FormOption(option, formClass1, optionEnum));
            }
        });

        return options;
    }

    private static void getDefaultOptions(@NotNull Project project, @NotNull PhpClass phpClass, @NotNull FormClass formClass, @NotNull FormOptionVisitor visitor) {

        for(String methodName: new String[] {"setDefaultOptions", "configureOptions"}) {

            Method method = phpClass.findMethodByName(methodName);
            if(method == null) {
                continue;
            }

            Collection<MethodReference> tests = PsiTreeUtil.findChildrenOfType(method, MethodReference.class);
            for(MethodReference methodReference: tests) {

                if(PhpElementsUtil.isEqualMethodReferenceName(methodReference, "setDefaults")) {
                    PsiElement[] parameters = methodReference.getParameters();
                    if(parameters.length > 0 && parameters[0] instanceof ArrayCreationExpression) {
                        for(Map.Entry<String, PsiElement> entry: PhpElementsUtil.getArrayCreationKeyMap((ArrayCreationExpression) parameters[0]).entrySet()) {
                            visitor.visit(entry.getValue(), entry.getKey(), formClass, FormOptionEnum.DEFAULT);
                        }
                    }

                } else {
                    // ->setRequired(['test', 'test2'])
                    for(String currentMethod: new String[] {"setRequired", "setOptional", "setDefined"}) {
                        if(PhpElementsUtil.isEqualMethodReferenceName(methodReference, currentMethod)) {
                            PsiElement[] parameters = methodReference.getParameters();
                            if(parameters.length > 0 && parameters[0] instanceof ArrayCreationExpression) {
                                for (Map.Entry<String, PsiElement> entry : PhpElementsUtil.getArrayValuesAsMap((ArrayCreationExpression) parameters[0]).entrySet()) {
                                    visitor.visit(entry.getValue(), entry.getKey(), formClass, FormOptionEnum.getEnum(currentMethod));
                                }
                            }
                            break;
                        }
                    }
                }

                // support: parent::setDefaultOptions($resolver)
                // Symfony\Component\Form\Extension\Core\Type\FormType:setDefaultOptions
                if(PhpElementsUtil.isEqualMethodReferenceName(methodReference, methodName) && methodReference.getReferenceType() == PhpModifier.State.PARENT) {
                    PsiElement parentMethod = PhpElementsUtil.getPsiElementsBySignatureSingle(project, methodReference.getSignature());
                    if(parentMethod instanceof Method) {
                        PhpClass phpClassInner = ((Method) parentMethod).getContainingClass();
                        if(phpClassInner != null) {
                            // @TODO only use setDefaultOptions, recursive call get setDefaults again
                            getDefaultOptions(project, phpClassInner, formClass, visitor);
                        }
                    }
                }

            }

        }

    }

    /**
     * Build completion lookup element for form options
     * Reformat class name to make it more readable
     *
     * @param formOption Extension or a default option
     * @return lookup element
     */
    public static LookupElement getOptionLookupElement(FormOption formOption) {

        String typeText = formOption.getFormClass().getPhpClass().getPresentableFQN();
        if(typeText.lastIndexOf("\\") != -1) {
            typeText = typeText.substring(typeText.lastIndexOf("\\") + 1);
            if(typeText.endsWith("Extension")) {
                typeText = typeText.substring(0, typeText.length() - 9);
            }
        }

        return LookupElementBuilder.create(formOption.getOption())
            .withTypeText(typeText, true)
            .withIcon(formOption.getFormClass().isWeak() ? Symfony2Icons.FORM_EXTENSION_WEAK : Symfony2Icons.FORM_EXTENSION);
    }

    @NotNull
    public static Collection<PsiElement> getFormExtensionsKeysTargets(@NotNull StringLiteralExpression psiElement, String... formTypes) {
        Map<String, FormOption> extensionKeys = FormOptionsUtil.getFormExtensionKeys(psiElement.getProject(), formTypes);
        String value = psiElement.getContents();

        if(!extensionKeys.containsKey(value)) {
            return Collections.emptyList();
        }

        Collection<PsiElement> psiElements = new HashSet<>();
        PhpClass phpClass = extensionKeys.get(value).getFormClass().getPhpClass();

        // Symfony <= 2.7 and > 2.7 api level search
        for (String methodName : new String[]{"setDefaultOptions", "configureOptions"}) {
            Method method = phpClass.findMethodByName(methodName);
            if(method == null) {
                continue;
            }

             ContainerUtil.addIfNotNull(
                 psiElements,
                 PhpElementsUtil.findArrayKeyValueInsideReference(method, "setDefaults", value)
             );
        }

        return psiElements;
    }

    public static Collection<LookupElement> getFormExtensionKeysLookupElements(Project project, String... formTypes) {
        Collection<LookupElement> lookupElements = new ArrayList<>();

        for(FormOption formOption: FormOptionsUtil.getFormExtensionKeys(project, formTypes).values()) {
            lookupElements.add(FormOptionsUtil.getOptionLookupElement(formOption));
        }

        return lookupElements;
    }

    @Deprecated
    @NotNull
    public static Collection<PsiElement> getDefaultOptionTargets(@NotNull StringLiteralExpression element, @NotNull String formType) {

        final String value = element.getContents();
        if(StringUtils.isBlank(value)) {
            return Collections.emptySet();
        }

        final Collection<PsiElement> psiElements = new ArrayList<>();

        FormOptionsUtil.getFormDefaultKeys(element.getProject(), formType, (psiElement, option, formClass, optionEnum) -> {
            if(option.equals(value)) {
                psiElements.add(psiElement);
            }
        });

        return psiElements;
    }

    @NotNull
    public static Collection<LookupElement> getDefaultOptionLookupElements(@NotNull Project project, @NotNull String formType) {
        Collection<LookupElement> lookupElements = new ArrayList<>();
        FormOptionsUtil.getFormDefaultKeys(project, formType, new FormOptionLookupVisitor(lookupElements));
        return lookupElements;
    }

}
