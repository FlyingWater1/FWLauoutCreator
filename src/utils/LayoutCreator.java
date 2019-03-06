package utils;

import com.intellij.codeInsight.actions.ReformatCodeProcessor;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.impl.source.tree.PsiWhiteSpaceImpl;
import com.intellij.psi.search.EverythingGlobalScope;
import entity.Element;
import org.apache.http.util.TextUtils;
import org.bouncycastle.math.ec.ECLookupTable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;


public class LayoutCreator extends WriteCommandAction.Simple {

    protected PsiFile mFile;
    protected Project mProject;
    protected PsiClass mClass;
    protected ArrayList<Element> mElements;
    protected PsiElementFactory mFactory;
    protected String mLayoutFileName;
    protected String mFieldNamePrefix;
    protected boolean mCreateHolder;

    public LayoutCreator(PsiFile file, PsiClass clazz, String command, ArrayList<Element> elements, String layoutFileName, String fieldNamePrefix, boolean createHolder) {
        super(clazz.getProject(), command);

        mFile = file;
        mProject = clazz.getProject();
        mClass = clazz;
        mElements = elements;
        mFactory = JavaPsiFacade.getElementFactory(mProject);
        mLayoutFileName = layoutFileName;
        mFieldNamePrefix = fieldNamePrefix;
        mCreateHolder = createHolder;
    }

    @Override
    public void run() throws Throwable {
        if (mCreateHolder) {
            generateAdapter();
        } else {
            generateFields();
            generateFindViewById();
        }

        // reformat class
        JavaCodeStyleManager styleManager = JavaCodeStyleManager.getInstance(mProject);
        styleManager.optimizeImports(mFile);
        styleManager.shortenClassReferences(mClass);
        new ReformatCodeProcessor(mProject, mClass.getContainingFile(), null, false).runWithoutProgress();
    }

    /**
     * Create ViewHolder for adapters with injections
     */
    protected void generateAdapter() {
        // view holder class
        String holderClassName = Utils.getViewHolderClassName();
        StringBuilder holderBuilder = new StringBuilder();

        // generator of view holder class
        StringBuilder generator = new StringBuilder();
        generator.append("public " + holderClassName + "(android.view.View rootView) {\n");

        // rootView
        String rootViewName = "rootView";
        holderBuilder.append("public " + "android.view.View " + rootViewName + ";\n");
        generator.append("this." + rootViewName + " = " + rootViewName + ";\n");

        for (Element element : mElements) {
            if (!element.used) {
                continue;
            }

            // field
            holderBuilder.append("public " + element.name + " " + element.getFieldName() + ";\n");

            // findViewById in generator
            generator.append("this." + element.getFieldName() + " = (" + element.name + ") "
                    + rootViewName + ".findViewById(" + element.getFullID() + ");\n");
        }
        generator.append("}\n");

        holderBuilder.append(generator.toString());

        PsiClass viewHolder = mFactory.createClassFromText(holderBuilder.toString(), mClass);
        viewHolder.setName(holderClassName);
        mClass.add(viewHolder);
        mClass.addBefore(mFactory.createKeyword("public", mClass), mClass.findInnerClassByName(holderClassName, true));
        mClass.addBefore(mFactory.createKeyword("static", mClass), mClass.findInnerClassByName(holderClassName, true));
    }

    /**
     * Create fields for injections inside main class
     */
    protected void generateFields() {
        for (Iterator<Element> iterator = mElements.iterator(); iterator.hasNext(); ) {
            Element element = iterator.next();

            if (!element.used) {
                iterator.remove();
                continue;
            }

            // remove duplicate field
            PsiField[] fields = mClass.getFields();
            boolean duplicateField = false;
            for (PsiField field : fields) {
                String name = field.getName();
                if (name != null && name.equals(element.getFieldName())) {
                    duplicateField = true;
                    break;
                }
            }

            if (!duplicateField) {
                mClass.add(mFactory.createFieldFromText("private " + element.name + " " + element.getFieldName() + ";", mClass));
            }
        }
    }

    protected void generateFindViewById() {
        PsiClass activityClass = JavaPsiFacade.getInstance(mProject).findClass(
                "android.app.Activity", new EverythingGlobalScope(mProject));
        PsiClass compatActivityClass = JavaPsiFacade.getInstance(mProject).findClass(
                "android.support.v7.app.AppCompatActivity", new EverythingGlobalScope(mProject));
        PsiClass fragmentClass = JavaPsiFacade.getInstance(mProject).findClass(
                "android.app.Fragment", new EverythingGlobalScope(mProject));
        PsiClass supportFragmentClass = JavaPsiFacade.getInstance(mProject).findClass(
                "android.support.v4.app.Fragment", new EverythingGlobalScope(mProject));

        // Check for Activity class
        if ((activityClass != null && mClass.isInheritor(activityClass, true))
                || (compatActivityClass != null && mClass.isInheritor(compatActivityClass, true))
                || mClass.getName().contains("Activity")) {
            generatorLayoutCode("this", null);
        } else if ((fragmentClass != null && mClass.isInheritor(fragmentClass, true)) || (supportFragmentClass != null && mClass.isInheritor(supportFragmentClass, true))) {
            generatorLayoutCode("this", "view");
        } else {
            generatorLayoutCode("this", "view");
        }
    }

    private void generatorLayoutCode(String contextName, String findPre) {
        List<Element> editTextElements = new ArrayList<>();
        List<Element> clickableElements = new ArrayList<>();
        List<Element> itemClickableElements = new ArrayList<>();

        // generator findViewById code in initView() method
        StringBuilder initView = new StringBuilder();
        if (TextUtils.isEmpty(findPre)) {
            initView.append("private void findId() {\n");
        } else {
            initView.append("private void findId(View " + findPre + ") {\n");
        }

        for (Element element : mElements) {
            String pre = TextUtils.isEmpty(findPre) ? "" : findPre + ".";
            initView.append(element.getFieldName() + " = (" + element.name + ") " + pre + "findViewById(" + element.getFullID() + ");\n");

            // set flag
            if (element.isEditText) {
                editTextElements.add(element);
            }
            if (element.isClickable) {
                clickableElements.add(element);
            }
            if (element.isItemClickable) {
                itemClickableElements.add(element);
            }
        }

        // generator EditText validate code if need
        StringBuilder sbEditText = new StringBuilder();
        if (editTextElements.size() > 0) {

            sbEditText.append("private void submit() {\n");
            sbEditText.append("\t\t// validate\n");

            for (Element element : editTextElements) {
                // generator EditText string name
                String idName = element.id;
                int index = idName.lastIndexOf("_");
                String name = index == -1 ? idName : idName.substring(index + 1);
                if (name.equals(idName)) {
                    name += "String";
                }

                sbEditText.append("String " + name + " = " + idName + ".getText().toString().trim();\n");
                sbEditText.append("if(TextUtils.isEmpty(" + name + ")) {\n");
                // 提示的toast为EditText的hint文字,无hint时格式为"name不能为空"
                String emptyTint = name + "不能为空";
                String hint = element.xml.getAttributeValue("android:hint");
                if (!TextUtils.isEmpty(hint) && !hint.startsWith("@string")) {
                    emptyTint = hint;
                }
                sbEditText.append("Toast.makeText(" + contextName + ", \"" + emptyTint + "\", Toast.LENGTH_SHORT).show();\n");
                sbEditText.append("return;\n");
                sbEditText.append("}\n");
                sbEditText.append("\n");
            }

            sbEditText.append("\t\t// TODO validate success, do something\n");
            sbEditText.append("\n\n}\n");
        }

        // generator clickable code if need
        StringBuilder sbClickable = new StringBuilder();
        if (clickableElements.size() > 0) {
            // let class implement OnClickListener
            PsiReferenceList implementsList = mClass.getImplementsList();
            if (implementsList != null) {
                PsiJavaCodeReferenceElement[] referenceElements = implementsList.getReferenceElements();
                boolean hasImpl = false;
                for (PsiJavaCodeReferenceElement re : referenceElements) {
                    hasImpl = re.getText().contains("OnClickListener");
                }
                // add implement if not exist
                if (!hasImpl) {
                    PsiJavaCodeReferenceElement pjcre = mFactory.createReferenceElementByFQClassName(
                            "android.view.View.OnClickListener", mClass.getResolveScope());
                    implementsList.add(pjcre);
                }
            }

            initView.append("\n");

            sbClickable.append("@Override public void onClick(View v) {\n")
                    .append("switch (v.getId()) {\n");

            for (Element element : clickableElements) {
                // generator setOnClickListener code in initView()
                initView.append(element.getFieldName() + ".setOnClickListener(this);\n");

                // generator override public void onClick(View v) method
                sbClickable.append("case " + element.getFullID() + " :\n\nbreak;\n");
            }
            sbClickable.append("}\n}");
        }

        // generator itemClickable code if need
        for (Element element : itemClickableElements) {
            // generator setOnClickListener code in initView()
            initView.append(element.getFieldName() + ".setOnItemClickListener(new AdapterView.OnItemClickListener() {\n");
            initView.append("@Override\n");
            initView.append("public void onItemClick(AdapterView<?> parent, View view, int position, long id) {\n\n");
            initView.append("}\n");
            initView.append("});\n");
        }

        initView.append("}\n");

        PsiMethod[] initViewMethods = mClass.findMethodsByName("findId", false);
        if (initViewMethods.length > 0 && initViewMethods[0].getBody() != null) {
            // already have method
            // append non-repeated field
            PsiCodeBlock initViewMethodBody = initViewMethods[0].getBody();

            for (Element element : mElements) {

                // append findViewById
                String pre = TextUtils.isEmpty(findPre) ? "" : findPre + ".";
                String s2 = element.getFieldName() + " = (" + element.name + ") " + pre + "findViewById(" + element.getFullID() + ");";
                initViewMethodBody.add(mFactory.createStatementFromText(s2, initViewMethods[0]));

                // append setOnClickListener
                String s1 = element.getFieldName() + ".setOnClickListener(this);";
                initViewMethodBody.add(mFactory.createStatementFromText(s1, initViewMethods[0]));
            }
        } else {
            // new method
            mClass.add(mFactory.createMethodFromText(initView.toString(), mClass));
        }

        if (clickableElements.size() > 0) {
            PsiMethod[] onClickMethods = mClass.findMethodsByName("onClick", false);
            if (onClickMethods.length > 0 && onClickMethods[0].getBody() != null) {
                // already have method
                // append non-repeated field
                PsiCodeBlock onClickMethodBody = onClickMethods[0].getBody();

                for (PsiElement element : onClickMethodBody.getChildren()) {
                    if (element instanceof PsiSwitchStatement) {
                        PsiSwitchStatement switchStatement = (PsiSwitchStatement) element;
                        PsiCodeBlock body = switchStatement.getBody();
                        if (body != null) {
                            for (Element clickableElement : clickableElements) {
                                String caseStr = "case " + clickableElement.getFullID() + " :";
                                body.add(mFactory.createStatementFromText(caseStr, body));
                                body.add(mFactory.createStatementFromText("break;", body));
                            }
                        }
                        break;
                    }
                }
            } else {
                // new method
                mClass.add(mFactory.createMethodFromText(sbClickable.toString(), mClass));
            }
        }

        if (editTextElements.size() > 0) {
            mClass.add(mFactory.createMethodFromText(sbEditText.toString(), mClass));
        }
    }
}