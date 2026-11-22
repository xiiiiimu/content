"""Generate a separate same-thread-warmup plan; never edits the synchronous JMX."""
from pathlib import Path
import xml.etree.ElementTree as E

def prop(parent, name, value, kind='stringProp'):
    E.SubElement(parent,kind,{'name':name}).text=str(value)

def generate(destination):
    root=E.Element('jmeterTestPlan',version='1.2',properties='5.0',jmeter='5.6.3')
    outer=E.SubElement(root,'hashTree')
    plan=E.SubElement(outer,'TestPlan',testname='V23 paced measurement',testclass='TestPlan',enabled='true')
    prop(plan,'TestPlan.functional_mode','false','boolProp')
    tree=E.SubElement(outer,'hashTree')
    group=E.SubElement(tree,'ThreadGroup',testclass='ThreadGroup',testname='v23-workers',enabled='true')
    prop(group,'ThreadGroup.num_threads','${__P(workers,128)}')
    prop(group,'ThreadGroup.ramp_time','1')
    prop(group,'ThreadGroup.on_sample_error','continue')
    loop=E.SubElement(group,'elementProp',name='ThreadGroup.main_controller',elementType='LoopController')
    prop(loop,'LoopController.loops','1');prop(loop,'LoopController.continue_forever','false','boolProp')
    gt=E.SubElement(tree,'hashTree')
    # Repeatedly prepare a warmup or formal row; terminate before issuing extra HTTP.
    while_node=E.SubElement(gt,'WhileController',testclass='WhileController',testname='until slots exhausted',enabled='true')
    prop(while_node,'WhileController.condition','${__groovy(vars.get("continue") != "false")}')
    wt=E.SubElement(gt,'hashTree')
    prepare=E.SubElement(wt,'JSR223Sampler',testclass='JSR223Sampler',testname='prepare (ignored)',enabled='true')
    prop(prepare,'scriptLanguage','groovy');prop(prepare,'cacheKey','true')
    prop(prepare,'filename',str(Path('infra/jmeter/v23-prepare.groovy').resolve()).replace('\\','/'))
    E.SubElement(wt,'hashTree')
    condition=E.SubElement(wt,'IfController',testclass='IfController',testname='HTTP slot exists',enabled='true')
    prop(condition,'IfController.condition','${runHttp}');prop(condition,'IfController.useExpression','true','boolProp')
    it=E.SubElement(wt,'hashTree')
    old=E.parse('infra/jmeter/commerce-sale.jmx').getroot()
    import copy
    sampler=copy.deepcopy(next(x for x in old.iter('HTTPSamplerProxy') if x.get('testname')=='POST-purchase'))
    sampler.set('testname','${phaseLabel}')
    sampler.find("stringProp[@name='HTTPSampler.path']").text='${__P(apiBase,http://127.0.0.1:28080)}/api/sale-campaigns/${campaign}/purchase'
    for n in ('connect_timeout','response_timeout'):
        sampler.find("stringProp[@name='HTTPSampler.%s']"%n).text='30000'
    prop(sampler,'HTTPSampler.implementation','HttpClient4')
    it.append(sampler);st=E.SubElement(it,'hashTree')
    hm=E.SubElement(st,'HeaderManager',testclass='HeaderManager',testname='authorized JSON',enabled='true')
    headers=E.SubElement(hm,'collectionProp',name='HeaderManager.headers')
    for k,v in [('Authorization','Bearer ${token}'),('Content-Type','application/json')]:
        h=E.SubElement(headers,'elementProp',name='',elementType='Header');prop(h,'Header.name',k);prop(h,'Header.value',v)
    E.SubElement(st,'hashTree')
    post=E.SubElement(st,'JSR223PostProcessor',testclass='JSR223PostProcessor',testname='safe response correlation',enabled='true')
    prop(post,'scriptLanguage','groovy');prop(post,'cacheKey','true')
    prop(post,'filename',str(Path('infra/jmeter/v23-response.groovy').resolve()).replace('\\','/'))
    E.SubElement(st,'hashTree')
    gui={'TestPlan':'TestPlanGui','ThreadGroup':'ThreadGroupGui','WhileController':'WhileControllerGui',
         'JSR223Sampler':'TestBeanGUI','IfController':'IfControllerPanel','HeaderManager':'HeaderPanel','JSR223PostProcessor':'TestBeanGUI'}
    for node in root.iter():
        if node.tag in gui:node.set('guiclass',gui[node.tag])
    E.indent(root);E.ElementTree(root).write(destination,encoding='UTF-8',xml_declaration=True)

if __name__=='__main__':
    import sys
    generate(sys.argv[1])
