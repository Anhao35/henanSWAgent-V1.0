"""Read-only graph contract tests; pass the original Dify export as argument."""
import copy
import importlib.util
import sys
import unittest
from pathlib import Path
import yaml

spec = importlib.util.spec_from_file_location('builder', Path(__file__).with_name('build-routed-workflow.py'))
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)
source = yaml.safe_load(Path(sys.argv.pop(1)).read_text(encoding='utf-8'))

class WorkflowContract(unittest.TestCase):
    def setUp(self):
        self.before = copy.deepcopy(source)
        self.result = builder.transform(source)
        self.graph = self.result['workflow']['graph']
        self.nodes = {n['id']: n for n in self.graph['nodes']}

    def test_original_preserved(self):
        self.assertEqual(source, self.before)
        mutable_titles = {'知识检索', '最终总结LLM', '资料解读-知识检索', '资料与文本解读（不调用安全工具）'}
        for node in source['workflow']['graph']['nodes']:
            updated = copy.deepcopy(self.nodes[node['id']])
            if node['data']['type'] == 'start' and not any(v.get('variable') == 'task_mode' for v in node['data'].get('variables', [])):
                updated['data']['variables'] = [v for v in updated['data']['variables'] if v['variable'] not in ('task_mode', 'platform_run_id')]
            if node['data'].get('title','').strip() in mutable_titles:
                continue
            self.assertEqual(node, updated)
        self.assertEqual(len(self.nodes), len(source['workflow']['graph']['nodes']) + 3)

    def test_read_branch_has_no_tools(self):
        gate_edges = [e for e in self.graph['edges'] if e['source'] == builder.GATE]
        self.assertEqual({e['sourceHandle'] for e in gate_edges}, {'true', 'false'})
        pending = [next(e['target'] for e in gate_edges if e['sourceHandle'] == 'true')]
        visited = set()
        while pending:
            node_id = pending.pop()
            if node_id in visited: continue
            visited.add(node_id)
            self.assertIn(self.nodes[node_id]['data']['type'], ('llm', 'answer', 'variable-aggregator', 'knowledge-retrieval', 'code'))
            pending.extend(e['target'] for e in self.graph['edges'] if e['source'] == node_id)
        self.assertEqual(visited, {builder.REFERENCE_KNOWLEDGE, builder.REFERENCE_KNOWLEDGE_FILTER, builder.CONTEXT, builder.READER, builder.ANSWER})

    def test_missing_attachment_has_guaranteed_query_fallback(self):
        context = self.nodes[builder.CONTEXT]['data']
        self.assertEqual(context['variables'], [['1779195036236', 'multimodal_context_text'], ['sys', 'query']])
        prompt = str(self.nodes[builder.READER]['data']['prompt_template'])
        self.assertNotIn('1779195036236.multimodal_context_text', prompt)
        self.assertIn(builder.CONTEXT+'.output', prompt)
        self.assertIn(builder.REFERENCE_KNOWLEDGE_FILTER+'.filtered_context', prompt)

    def test_main_knowledge_has_compact_query_and_hard_filter(self):
        main_knowledge = next(n for n in self.nodes.values() if n['data'].get('title','').strip() == '知识检索')
        config = main_knowledge['data']['multiple_retrieval_config']
        self.assertEqual(main_knowledge['data']['query_variable_selector'], [builder.MAIN_KNOWLEDGE_QUERY, 'retrieval_query'])
        self.assertEqual(config['weights']['keyword_setting']['keyword_weight'], 0.45)
        self.assertEqual(config['weights']['vector_setting']['vector_weight'], 0.55)
        final = next(n for n in self.nodes.values() if n['data'].get('title') == '最终总结LLM')
        self.assertFalse(final['data']['context']['enabled'])
        self.assertIn(builder.MAIN_KNOWLEDGE_FILTER + '.filtered_context', str(final['data']['prompt_template']))
        self.assertNotIn('{{#context#}}', str(final['data']['prompt_template']))

    def test_filter_rejects_other_cve_numbers(self):
        namespace = {}
        exec(builder.KNOWLEDGE_FILTER_CODE, namespace)
        result = namespace['main'](
            knowledge_result=[
                {'content':'CVE-2024-36401 是 GeoServer 漏洞。'},
                {'content':'CVE-2024-3400 的受影响版本和缓解措施。'},
                {'content':'CVE-2024-55591 是另一个漏洞。'},
            ],
            ioc_list_json='[{"ioc_type":"cve","ioc":"CVE-2024-3400"}]',
            user_query='请研判 CVE-2024-3400 并说明修复建议。',
        )
        self.assertEqual(result['filter_status'], 'exact_hit')
        self.assertEqual(result['hit_count'], 1)
        self.assertEqual(result['rejected_count'], 2)
        self.assertIn('CVE-2024-3400', result['filtered_context'])
        self.assertNotIn('CVE-2024-36401', result['filtered_context'])
        self.assertNotIn('CVE-2024-55591', result['filtered_context'])

    def test_filter_returns_explicit_no_direct_hit(self):
        namespace = {}
        exec(builder.KNOWLEDGE_FILTER_CODE, namespace)
        result = namespace['main'](
            knowledge_result=[{'content':'CVE-2024-36401 的修复说明。'}],
            ioc_list_json='[{"ioc_type":"cve","ioc":"CVE-2024-3400"}]',
            user_query='CVE-2024-3400',
        )
        self.assertEqual(result['filter_status'], 'no_direct_hit')
        self.assertEqual(result['hit_count'], 0)
        self.assertIn('已剔除 1 条', result['filtered_context'])
        self.assertNotIn('CVE-2024-36401', result['filtered_context'])

    def test_edges_and_context(self):
        for edge in self.graph['edges']:
            self.assertIn(edge['source'], self.nodes)
            self.assertIn(edge['target'], self.nodes)
        context = self.nodes['1779195036236']['data']
        self.assertIn('multimodal_context_text', context.get('outputs', {}))
        start = next(n for n in self.nodes.values() if n['data']['type'] == 'start')
        mode = next(v for v in start['data']['variables'] if v['variable'] == 'task_mode')
        self.assertEqual(mode['options'], ['AUTO', 'SECURITY', 'READ', 'EXPLAIN'])

    def test_duplicate_transform_rejected(self):
        with self.assertRaises(ValueError): builder.transform(self.result)

if __name__ == '__main__': unittest.main()
