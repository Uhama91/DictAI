from __future__ import annotations

import copy
import hashlib
import importlib
import json
import os
from pathlib import Path
from types import SimpleNamespace
import sys

import pytest


ROOT = Path(
    os.environ.get("GEMMA3_WORKSPACE", Path(__file__).resolve().parents[1])
).expanduser().resolve()
for import_root in (ROOT, ROOT / "src"):
    if str(import_root) not in sys.path:
        sys.path.insert(0, str(import_root))


def test_versioned_mlp_runner_config_and_helper_are_present():
    assert (ROOT / "scripts" / "run_gemma3_repair_mlp_v2.py").is_file()
    assert (ROOT / "configs" / "gemma3_repair_mlp_v2.json").is_file()
    assert (ROOT / "src" / "asr_postclean" / "gemma3_mlp_adapter.py").is_file()


def _tiny_model_class():
    import torch
    from torch import nn

    class TinyBlock(nn.Module):
        def __init__(self):
            super().__init__()
            self.q_proj = nn.Linear(4, 4)
            self.k_proj = nn.Linear(4, 4)
            self.v_proj = nn.Linear(4, 4)
            self.o_proj = nn.Linear(4, 4)
            self.gate_proj = nn.Linear(4, 8)
            self.up_proj = nn.Linear(4, 8)
            self.down_proj = nn.Linear(8, 4)

        def forward(self, values):
            attended = self.o_proj(
                self.q_proj(values) + self.k_proj(values) + self.v_proj(values)
            )
            mlp = self.down_proj(
                torch.tanh(self.gate_proj(values)) * self.up_proj(values)
            )
            return values + attended + mlp

    class TinyCausalModel(nn.Module):
        def __init__(self):
            super().__init__()
            self.layers = nn.ModuleList([TinyBlock(), TinyBlock()])
            self.lm_head = nn.Linear(4, 7)

        def forward(self, input_ids):
            values = torch.nn.functional.one_hot(input_ids, num_classes=4).float()
            for layer in self.layers:
                values = layer(values)
            return self.lm_head(values)

    return TinyCausalModel


def _lora_config(target_modules, *, task_type=None):
    from peft import LoraConfig

    return LoraConfig(
        r=2,
        lora_alpha=4,
        lora_dropout=0.0,
        target_modules=list(target_modules),
        bias="none",
        task_type=task_type,
    )


def _saved_v3_attention_adapter(tmp_path):
    import torch
    from peft import get_peft_model

    module = importlib.import_module("asr_postclean.gemma3_mlp_adapter")
    torch.manual_seed(71)
    base = _tiny_model_class()()
    parent = get_peft_model(
        copy.deepcopy(base),
        _lora_config(module.ATTENTION_TARGET_MODULES),
    )
    with torch.no_grad():
        for name, parameter in parent.named_parameters():
            if "lora_B" in name:
                parameter.copy_(torch.randn_like(parameter))
    parent.eval()
    sample = {"input_ids": torch.tensor([[0, 1, 3, 2]])}
    expected = parent(**sample).detach()
    parent_dir = tmp_path / "parent-v3"
    parent.save_pretrained(parent_dir, safe_serialization=True)
    return module, base, parent_dir, sample, expected


def test_v2_configuration_is_locked_to_same_recipe_and_seven_targets(monkeypatch):
    runner = importlib.import_module("scripts.run_gemma3_repair_mlp_v2")
    config = runner.load_candidate_config(
        ROOT / "configs" / "gemma3_repair_mlp_v2.json"
    )
    adapter = importlib.import_module("asr_postclean.gemma3_mlp_adapter")
    assert tuple(config["training"]["target_modules"]) == adapter.ALL_TARGET_MODULES
    assert config["training"]["expected_updates"] == 64
    assert config["training"]["learning_rate"] == 2e-5
    monkeypatch.setattr(runner, "EXECUTION_REVIEWED", False)
    with pytest.raises(runner.RunnerError, match="execution_not_reviewed"):
        runner.require_execution_reviewed("training")
    assert "gemma3_mlp_adapter" in runner._sealed_helper_hashes()

    altered = copy.deepcopy(config)
    altered["training"]["target_modules"].remove("down_proj")
    with pytest.raises(runner.RunnerError, match="target modules"):
        runner.validate_candidate_config(altered)


def test_expansion_preserves_attention_outputs_and_zeroes_only_new_mlp_b(tmp_path):
    import torch
    from peft import get_peft_model

    module, base, parent_dir, sample, expected = _saved_v3_attention_adapter(tmp_path)
    config = SimpleNamespace(lora_r=2, lora_alpha=4, lora_dropout=0.0)
    expanded = get_peft_model(
        copy.deepcopy(base), module.make_expanded_lora_config(config, task_type=None)
    )

    metadata = module.load_parent_attention_adapter(expanded, parent_dir)

    expanded.eval()
    actual = expanded(**sample).detach()
    torch.testing.assert_close(actual, expected, rtol=0.0, atol=1e-7)
    assert metadata["target_modules"] == list(module.ALL_TARGET_MODULES)
    assert metadata["inherited_attention_tensor_count"] == 16
    assert metadata["inherited_attention_exact_after_load"] is True
    assert metadata["loaded_attention_sha256"] == metadata["inherited_attention_sha256"]
    assert metadata["module_coverage"] == {target: 2 for target in module.ALL_TARGET_MODULES}
    assert metadata["inherited_attention_sha256"]
    assert metadata["new_mlp_b_zero"] is True
    assert metadata["new_mlp_b_tensor_count"] == 6
    assert metadata["trainable_parameter_count"] > 0

    named = dict(expanded.named_parameters())
    assert all(not parameter.requires_grad for name, parameter in named.items() if "lora_" not in name)
    assert all(
        torch.count_nonzero(parameter).item() == 0
        for name, parameter in named.items()
        if "lora_B" in name and any(f".{target}." in name for target in module.MLP_TARGET_MODULES)
    )


def test_expansion_rejects_missing_extra_wrong_shape_and_nonfinite_parent_tensors(tmp_path):
    import torch
    from peft import get_peft_model
    from safetensors.torch import load_file

    module, base, parent_dir, _, _ = _saved_v3_attention_adapter(tmp_path)
    config = SimpleNamespace(lora_r=2, lora_alpha=4, lora_dropout=0.0)
    expanded = get_peft_model(
        copy.deepcopy(base), module.make_expanded_lora_config(config, task_type=None)
    )
    state = load_file(str(parent_dir / "adapter_model.safetensors"), device="cpu")
    key = next(iter(state))

    missing = dict(state)
    del missing[key]
    with pytest.raises(ValueError, match="missing"):
        module.expand_attention_state_dict(expanded, missing)

    extra = dict(state)
    extra["unexpected.lora_A.default.weight"] = torch.ones(1)
    with pytest.raises(ValueError, match="unexpected"):
        module.expand_attention_state_dict(expanded, extra)

    wrong_shape = dict(state)
    wrong_shape[key] = torch.ones(1)
    with pytest.raises(ValueError, match="shape"):
        module.expand_attention_state_dict(expanded, wrong_shape)

    wrong_dtype = dict(state)
    wrong_dtype[key] = state[key].double()
    with pytest.raises(ValueError, match="dtype"):
        module.expand_attention_state_dict(expanded, wrong_dtype)

    nonfinite = dict(state)
    nonfinite[key] = torch.full_like(nonfinite[key], float("nan"))
    with pytest.raises(ValueError, match="finite"):
        module.expand_attention_state_dict(expanded, nonfinite)

    partial = get_peft_model(
        copy.deepcopy(base),
        _lora_config(module.ALL_TARGET_MODULES[:-1]),
    )
    with pytest.raises(ValueError, match="lacks required target module coverage"):
        module.expand_attention_state_dict(partial, state)


def test_expanded_adapter_trains_mlp_b_and_round_trips_with_seven_targets(tmp_path):
    import torch
    from peft import get_peft_model

    module, base, parent_dir, sample, _ = _saved_v3_attention_adapter(tmp_path)
    config = SimpleNamespace(lora_r=2, lora_alpha=4, lora_dropout=0.0)
    expanded = get_peft_model(
        copy.deepcopy(base), module.make_expanded_lora_config(config, task_type=None)
    )
    module.load_parent_attention_adapter(expanded, parent_dir)
    before = {
        name: parameter.detach().clone()
        for name, parameter in expanded.named_parameters()
        if "lora_B" in name and any(f".{target}." in name for target in module.MLP_TARGET_MODULES)
    }
    optimizer = torch.optim.SGD(
        [parameter for parameter in expanded.parameters() if parameter.requires_grad],
        lr=0.1,
    )
    optimizer.zero_grad()
    expanded(**sample).square().mean().backward()
    optimizer.step()
    after = dict(expanded.named_parameters())
    assert any(not torch.equal(before[name], after[name]) for name in before)

    expanded.eval()
    expected = expanded(**sample).detach()
    candidate_dir = tmp_path / "candidate"
    expanded.save_pretrained(candidate_dir, safe_serialization=True)
    reloaded_config = module.validate_expanded_adapter_config(candidate_dir)
    assert set(reloaded_config["target_modules"]) == set(module.ALL_TARGET_MODULES)
    reloaded = module.load_expanded_candidate_model(
        copy.deepcopy(base), candidate_dir, is_trainable=True
    )
    reloaded.eval()
    torch.testing.assert_close(reloaded(**sample).detach(), expected, rtol=0.0, atol=1e-7)
    assert any(parameter.requires_grad for name, parameter in reloaded.named_parameters() if "down_proj.lora_" in name)


def test_load_rejects_corruption_after_parent_attention_copy(monkeypatch, tmp_path):
    import torch
    import peft
    from peft import get_peft_model

    module, base, parent_dir, _, _ = _saved_v3_attention_adapter(tmp_path)
    config = SimpleNamespace(lora_r=2, lora_alpha=4, lora_dropout=0.0)
    expanded = get_peft_model(
        copy.deepcopy(base), module.make_expanded_lora_config(config, task_type=None)
    )
    original_load = peft.set_peft_model_state_dict

    def corrupt_after_load(model, state_dict, *args, **kwargs):
        result = original_load(model, state_dict, *args, **kwargs)
        parameter = next(
            parameter
            for name, parameter in model.named_parameters()
            if ".q_proj.lora_A." in name
        )
        with torch.no_grad():
            parameter.add_(1.0)
        return result

    monkeypatch.setattr(peft, "set_peft_model_state_dict", corrupt_after_load)
    with pytest.raises(module.AdapterExpansionError, match="exact attention inheritance"):
        module.load_parent_attention_adapter(expanded, parent_dir)


def _write_jsonl(path, rows):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        "".join(json.dumps(row, sort_keys=True) + "\n" for row in rows),
        encoding="utf-8",
    )
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _locked_child_dataset(path):
    train = [
        {
            "id": f"train-{index}",
            "group_id": f"train-group-{index}",
            "category": "daily/identity",
            "source": f"unique train source {index}",
            "target": f"Unique train source {index}.",
            "synthetic": True,
            "split": "train",
        }
        for index in range(256)
    ]
    valid = [
        {
            "id": f"dev-{index}",
            "group_id": f"dev-group-{index}",
            "category": "daily/identity",
            "source": f"unique dev source {index}",
            "target": f"Unique dev source {index}.",
            "synthetic": True,
            "split": "dev",
        }
        for index in range(32)
    ]
    train_sha = _write_jsonl(path / "train.jsonl", train)
    valid_sha = _write_jsonl(path / "valid.jsonl", valid)
    manifest = {
        "dataset": "gemma3-repair-v2-test",
        "files": {
            "train.jsonl": {"records": len(train), "sha256": train_sha},
            "valid.jsonl": {"records": len(valid), "sha256": valid_sha},
        },
    }
    manifest_path = path / "manifest.json"
    manifest_path.write_text(json.dumps(manifest, sort_keys=True) + "\n", encoding="utf-8")
    (path / "manifest.sha256").write_text(
        hashlib.sha256(manifest_path.read_bytes()).hexdigest() + "\n", encoding="utf-8"
    )
    return path


def test_prepare_seals_new_helper_and_rejects_mutation_before_worker(monkeypatch, tmp_path):
    runner = importlib.import_module("scripts.run_gemma3_repair_mlp_v2")
    package = tmp_path / "staged-src" / "asr_postclean"
    package.mkdir(parents=True)
    for name in (
        "__init__.py",
        "io.py",
        "manifest.py",
        "metrics.py",
        "edit_weighting.py",
        "prompting.py",
        "tokenization.py",
        "training.py",
        "evaluation.py",
        "gemma3_mlp_adapter.py",
    ):
        source = ROOT / "src" / "asr_postclean" / name
        (package / name).write_bytes(source.read_bytes())
    monkeypatch.setattr(runner, "SRC", package.parent)

    parent_manifest = tmp_path / "parent" / "manifest.json"
    parent_manifest.parent.mkdir()
    parent_manifest.write_text("{}\n", encoding="utf-8")
    parent_adapter = parent_manifest.parent / "best"
    parent_adapter.mkdir()
    child = _locked_child_dataset(tmp_path / "child")
    old_valid = tmp_path / "legacy" / "valid.jsonl"
    _write_jsonl(
        old_valid,
        [
            {
                "id": f"old-{index}",
                "group_id": f"old-group-{index}",
                "category": "daily/identity",
                "source": f"old source {index}",
                "target": f"Old source {index}.",
                "synthetic": True,
                "split": "valid",
            }
            for index in range(76)
        ],
    )

    def parent_seal(manifest_path, adapter_path, *, validator=None):
        del validator
        from asr_postclean.prompting import instruction_sha256

        return {
            "parent_manifest": str(Path(manifest_path).resolve()),
            "parent_manifest_sha256": runner._sha256_file(manifest_path),
            "parent_adapter": str(Path(adapter_path).resolve()),
            "parent_adapter_sha256": "fixed-parent-adapter-sha",
            "parent_dataset_revision": "fixed-parent-dataset-revision",
            "child_dataset_revision": None,
            "model_id": runner.MODEL_ID,
            "model_revision": runner.MODEL_REVISION,
            "prompt_version": runner.PROMPT_VERSION,
            "prompt_sha256": instruction_sha256(runner.PROMPT_VERSION),
        }

    monkeypatch.setattr(runner, "validate_parent_provenance", parent_seal)
    sealed = runner.prepare_run(
        config_path=ROOT / "configs" / "gemma3_repair_mlp_v2.json",
        child_dataset=child,
        old_valid_path=old_valid,
        parent_manifest=parent_manifest,
        parent_adapter=parent_adapter,
        output_dir=tmp_path / "prepared",
    )
    assert sealed["helper_sha256"] == runner._sealed_helper_hashes()
    assert "gemma3_mlp_adapter" in sealed["helper_sha256"]
    runner._validate_prepared_integrity(sealed)

    helper = package / "gemma3_mlp_adapter.py"
    helper.write_bytes(helper.read_bytes() + b"\n# mutation after prepare\n")
    with pytest.raises(runner.RunnerError, match="helper changed"):
        runner._validate_prepared_integrity(sealed)
