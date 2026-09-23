"""Safe expansion of the V3 attention LoRA to Gemma 3 attention plus MLP."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path
from typing import Any, Mapping

import torch


ATTENTION_TARGET_MODULES = ("q_proj", "k_proj", "v_proj", "o_proj")
MLP_TARGET_MODULES = ("gate_proj", "up_proj", "down_proj")
ALL_TARGET_MODULES = ATTENTION_TARGET_MODULES + MLP_TARGET_MODULES


class AdapterExpansionError(ValueError):
    """The V3 adapter cannot be copied exactly into the expanded LoRA model."""


def make_expanded_lora_config(config, *, task_type: str | None = "CAUSAL_LM"):
    """Build the fixed V2 LoRA shape while keeping rank and scale from V3."""
    from peft import LoraConfig

    return LoraConfig(
        r=int(config.lora_r),
        lora_alpha=int(config.lora_alpha),
        lora_dropout=float(config.lora_dropout),
        target_modules=list(ALL_TARGET_MODULES),
        bias="none",
        task_type=task_type,
    )


def _target_for_adapter_key(name: str) -> str | None:
    targets = [target for target in ALL_TARGET_MODULES if f".{target}.lora_" in name]
    if len(targets) == 1:
        return targets[0]
    return None


def _checkpoint_key_for_parameter(name: str) -> str:
    """PEFT checkpoints omit the runtime adapter-name segment used by parameters."""
    for factor in ("lora_A", "lora_B"):
        name = name.replace(f".{factor}.default.", f".{factor}.")
    return name


def _module_path_for_key(name: str, target: str) -> str:
    marker = f".{target}.lora_"
    return name.split(marker, 1)[0] + "." + target


def _adapter_parameters(model) -> dict[str, torch.nn.Parameter]:
    parameters = {
        name: parameter
        for name, parameter in model.named_parameters()
        if "lora_" in name
    }
    if not parameters:
        raise AdapterExpansionError("expanded model has no LoRA parameters")
    unknown = sorted(
        name for name in parameters if _target_for_adapter_key(name) is None
    )
    if unknown:
        raise AdapterExpansionError(f"expanded model has unexpected LoRA keys: {unknown}")
    return parameters


def _validate_module_coverage(
    model,
    parameters: Mapping[str, torch.nn.Parameter],
) -> dict[str, int]:
    coverage: dict[str, set[str]] = {target: set() for target in ALL_TARGET_MODULES}
    for name in parameters:
        target = _target_for_adapter_key(name)
        if target is not None:
            coverage[target].add(_module_path_for_key(name, target))
    missing = [target for target, paths in coverage.items() if not paths]
    if missing:
        raise AdapterExpansionError(
            f"expanded model lacks required target module coverage: {missing}"
        )
    for target, paths in coverage.items():
        for path in paths:
            keys = [
                name
                for name in parameters
                if _target_for_adapter_key(name) == target
                and _module_path_for_key(name, target) == path
            ]
            factors = {"lora_A" if ".lora_A." in name else "lora_B" for name in keys}
            if factors != {"lora_A", "lora_B"}:
                raise AdapterExpansionError(
                    f"target module {path} lacks a complete LoRA A/B pair"
                )
    get_base_model = getattr(model, "get_base_model", None)
    if not callable(get_base_model):
        raise AdapterExpansionError("expanded PEFT model cannot expose its base modules")
    expected_ids: dict[str, set[int]] = {target: set() for target in ALL_TARGET_MODULES}
    for name, module in get_base_model().named_modules():
        target = name.rsplit(".", 1)[-1]
        weight = getattr(module, "weight", None)
        if target in expected_ids and isinstance(weight, torch.Tensor) and weight.ndim == 2:
            expected_ids[target].add(id(module))
    actual_ids: dict[str, set[int]] = {target: set() for target in ALL_TARGET_MODULES}
    for name, module in model.named_modules():
        target = name.rsplit(".", 1)[-1]
        if target not in actual_ids or not hasattr(module, "lora_A"):
            continue
        actual_ids[target].add(id(module))
    incomplete = {
        target: {
            "missing": len(expected_ids[target] - actual_ids[target]),
            "unexpected": len(actual_ids[target] - expected_ids[target]),
        }
        for target in ALL_TARGET_MODULES
        if expected_ids[target] != actual_ids[target]
    }
    if incomplete:
        raise AdapterExpansionError(
            f"LoRA projection coverage is not exact by target and layer: {incomplete}"
        )
    return {target: len(paths) for target, paths in coverage.items()}


def _tensor_mapping_sha256(values: Mapping[str, torch.Tensor]) -> str:
    digest = hashlib.sha256()
    for name in sorted(values):
        tensor = values[name].detach().to(device="cpu").contiguous()
        digest.update(name.encode("utf-8"))
        digest.update(str(tensor.dtype).encode("ascii"))
        digest.update(json.dumps(list(tensor.shape), separators=(",", ":")).encode("ascii"))
        digest.update(tensor.view(torch.uint8).numpy().tobytes())
    return digest.hexdigest()


def expand_attention_state_dict(
    model,
    parent_state: Mapping[str, torch.Tensor],
) -> tuple[dict[str, torch.Tensor], dict[str, Any]]:
    """Return a complete seven-target adapter state seeded from exact V3 QKVO.

    The input checkpoint must contain exactly the attention LoRA tensors. New MLP
    A factors retain PEFT's deterministic seeded initialization and MLP B factors
    are explicitly zeroed so the initial function remains the V3 function.
    """
    parameters = _adapter_parameters(model)
    coverage = _validate_module_coverage(model, parameters)
    expected_attention = {
        _checkpoint_key_for_parameter(name): name
        for name in parameters
        if _target_for_adapter_key(name) in ATTENTION_TARGET_MODULES
    }
    actual_keys = set(parent_state)
    missing = sorted(set(expected_attention) - actual_keys)
    unexpected = sorted(actual_keys - set(expected_attention))
    if missing:
        raise AdapterExpansionError(f"parent adapter is missing attention keys: {missing}")
    if unexpected:
        raise AdapterExpansionError(f"parent adapter has unexpected keys: {unexpected}")

    expanded: dict[str, torch.Tensor] = {}
    inherited: dict[str, torch.Tensor] = {}
    new_mlp_b: list[str] = []
    for name, parameter in parameters.items():
        target = _target_for_adapter_key(name)
        if target in ATTENTION_TARGET_MODULES:
            checkpoint_key = _checkpoint_key_for_parameter(name)
            value = parent_state[checkpoint_key]
            if not isinstance(value, torch.Tensor):
                raise AdapterExpansionError(f"parent adapter value is not a tensor: {name}")
            if tuple(value.shape) != tuple(parameter.shape):
                raise AdapterExpansionError(
                    f"parent adapter shape differs for {name}: "
                    f"{tuple(value.shape)} != {tuple(parameter.shape)}"
                )
            if value.dtype != parameter.dtype:
                raise AdapterExpansionError(
                    f"parent adapter dtype differs for {name}: "
                    f"{value.dtype} != {parameter.dtype}"
                )
            if not torch.isfinite(value).all().item():
                raise AdapterExpansionError(f"parent adapter tensor is not finite: {name}")
            inherited[checkpoint_key] = value.detach().to(device="cpu").clone()
            expanded[checkpoint_key] = inherited[checkpoint_key]
        elif target in MLP_TARGET_MODULES and ".lora_B." in name:
            value = torch.zeros_like(parameter.detach(), device="cpu")
            expanded[_checkpoint_key_for_parameter(name)] = value
            new_mlp_b.append(name)
        else:
            expanded[_checkpoint_key_for_parameter(name)] = parameter.detach().to(device="cpu").clone()

    inherited_sha = _tensor_mapping_sha256(inherited)
    trainable = {
        name: parameter
        for name, parameter in model.named_parameters()
        if parameter.requires_grad
    }
    non_lora_trainable = sorted(name for name in trainable if "lora_" not in name)
    if non_lora_trainable:
        raise AdapterExpansionError(
            f"base model unexpectedly has trainable parameters: {non_lora_trainable}"
        )
    trainable_targets = {
        target
        for name in trainable
        if (target := _target_for_adapter_key(name)) is not None
    }
    if trainable_targets != set(ALL_TARGET_MODULES):
        raise AdapterExpansionError(
            "trainable LoRA parameters do not cover all seven targets: "
            f"{sorted(trainable_targets)}"
        )
    metadata = {
        "target_modules": list(ALL_TARGET_MODULES),
        "module_coverage": coverage,
        "inherited_attention_tensor_count": len(inherited),
        "inherited_attention_sha256": inherited_sha,
        "new_mlp_b_tensor_count": len(new_mlp_b),
        "new_mlp_b_keys": sorted(new_mlp_b),
        "new_mlp_b_zero": all(
            torch.count_nonzero(expanded[_checkpoint_key_for_parameter(name)]).item() == 0
            for name in new_mlp_b
        ),
        "trainable_parameter_count": sum(int(parameter.numel()) for parameter in trainable.values()),
        "trainable_parameter_names": sorted(trainable),
    }
    nonfinite = [
        name for name, value in expanded.items() if not torch.isfinite(value).all().item()
    ]
    if nonfinite:
        raise AdapterExpansionError(f"expanded adapter contains nonfinite tensors: {nonfinite}")
    return expanded, metadata


def _read_adapter_config(adapter_dir: str | Path) -> dict[str, Any]:
    path = Path(adapter_dir).expanduser().resolve() / "adapter_config.json"
    if not path.is_file():
        raise AdapterExpansionError(f"adapter config is absent: {path}")
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise AdapterExpansionError("adapter config must be an object")
    return value


def validate_expanded_adapter_config(
    adapter_dir: str | Path,
    *,
    expected_config=None,
) -> dict[str, Any]:
    """Reject candidates that would fall back to the historical four-target loader."""
    config = _read_adapter_config(adapter_dir)
    targets = config.get("target_modules", ())
    if not isinstance(targets, (list, tuple, set)) or set(targets) != set(ALL_TARGET_MODULES):
        raise AdapterExpansionError(
            "candidate adapter target_modules must contain exactly the seven V2 targets"
        )
    if int(config.get("r", -1)) <= 0 or int(config.get("lora_alpha", -1)) <= 0:
        raise AdapterExpansionError("candidate adapter rank or alpha is invalid")
    if config.get("bias") != "none":
        raise AdapterExpansionError("candidate adapter bias setting is not locked to none")
    if not torch.isfinite(torch.tensor(float(config.get("lora_dropout", float("nan"))))):
        raise AdapterExpansionError("candidate adapter dropout is not finite")
    if expected_config is not None:
        expected_r = (
            expected_config.get("lora_r")
            if isinstance(expected_config, Mapping)
            else getattr(expected_config, "lora_r", None)
        )
        expected_alpha = (
            expected_config.get("lora_alpha")
            if isinstance(expected_config, Mapping)
            else getattr(expected_config, "lora_alpha", None)
        )
        expected_dropout = (
            expected_config.get("lora_dropout")
            if isinstance(expected_config, Mapping)
            else getattr(expected_config, "lora_dropout", None)
        )
        if int(config["r"]) != int(expected_r) or int(config["lora_alpha"]) != int(expected_alpha):
            raise AdapterExpansionError("candidate adapter rank or alpha differs from the locked V2 config")
        if float(config["lora_dropout"]) != float(expected_dropout):
            raise AdapterExpansionError("candidate adapter dropout differs from the locked V2 config")
    return config


def load_parent_attention_adapter(model, adapter_dir: str | Path) -> dict[str, Any]:
    """Copy the immutable V3 QKVO tensors into an already-expanded PEFT model."""
    from peft import set_peft_model_state_dict

    directory = Path(adapter_dir).expanduser().resolve()
    parent_config = _read_adapter_config(directory)
    parent_targets = parent_config.get("target_modules", ())
    if not isinstance(parent_targets, (list, tuple, set)) or set(parent_targets) != set(
        ATTENTION_TARGET_MODULES
    ):
        raise AdapterExpansionError("parent adapter must contain exactly the V3 QKVO targets")
    expected = model.peft_config.get("default")
    if expected is None:
        raise AdapterExpansionError("expanded PEFT model has no default adapter config")
    for file_name in ("adapter_model.safetensors", "adapter_model.bin"):
        path = directory / file_name
        if not path.is_file():
            continue
        if file_name.endswith(".safetensors"):
            from safetensors.torch import load_file

            state = load_file(str(path), device="cpu")
        else:
            state = torch.load(path, map_location="cpu", weights_only=True)
        for field, current_field in (("r", "r"), ("lora_alpha", "lora_alpha")):
            if int(parent_config.get(field, -1)) != int(getattr(expected, current_field)):
                raise AdapterExpansionError(f"parent adapter {field} differs from expanded config")
        if float(parent_config.get("lora_dropout", -1.0)) != float(expected.lora_dropout):
            raise AdapterExpansionError("parent adapter dropout differs from expanded config")
        expanded, metadata = expand_attention_state_dict(model, state)
        load_result = set_peft_model_state_dict(model, expanded)
        missing_lora = [
            key for key in getattr(load_result, "missing_keys", ()) if "lora_" in key
        ]
        unexpected_lora = [
            key for key in getattr(load_result, "unexpected_keys", ()) if "lora_" in key
        ]
        if missing_lora or unexpected_lora:
            raise AdapterExpansionError(
                "expanded adapter state did not load completely: "
                f"missing={missing_lora}, unexpected={unexpected_lora}"
            )
        loaded_parameters = _adapter_parameters(model)
        loaded_by_checkpoint_key = {
            _checkpoint_key_for_parameter(name): parameter.detach().to(device="cpu")
            for name, parameter in loaded_parameters.items()
        }
        inherited_parent = {
            key: value.detach().to(device="cpu")
            for key, value in state.items()
        }
        inherited_keys = {
            key: loaded_by_checkpoint_key[key]
            for key in inherited_parent
        }
        parent_sha = _tensor_mapping_sha256(inherited_parent)
        loaded_sha = _tensor_mapping_sha256(inherited_keys)
        exact = all(
            inherited_parent[key].dtype == inherited_keys[key].dtype
            and torch.equal(inherited_parent[key], inherited_keys[key])
            for key in inherited_parent
        )
        mlp_b = {
            name: parameter.detach()
            for name, parameter in loaded_parameters.items()
            if _target_for_adapter_key(name) in MLP_TARGET_MODULES
            and ".lora_B." in name
        }
        mlp_b_zero = bool(mlp_b) and all(
            torch.count_nonzero(parameter).item() == 0 for parameter in mlp_b.values()
        )
        if not exact or not mlp_b_zero or parent_sha != metadata["inherited_attention_sha256"]:
            raise AdapterExpansionError(
                "loaded adapter failed exact attention inheritance or zero-MLP-B verification"
            )
        metadata["inherited_attention_sha256"] = parent_sha
        metadata["loaded_attention_sha256"] = loaded_sha
        metadata["inherited_attention_exact_after_load"] = exact
        metadata["new_mlp_b_zero"] = mlp_b_zero
        del state
        return metadata
    raise AdapterExpansionError(f"parent adapter weights are absent: {directory}")


def load_expanded_candidate_model(
    base_model,
    adapter_dir: str | Path,
    *,
    is_trainable: bool = False,
    expected_config=None,
):
    """Load a saved seven-target V2 adapter without the four-target V1 loader."""
    from peft import PeftModel

    validate_expanded_adapter_config(adapter_dir, expected_config=expected_config)
    return PeftModel.from_pretrained(
        base_model,
        str(Path(adapter_dir).expanduser().resolve()),
        is_trainable=is_trainable,
    )


def load_expanded_training_model(config, *, revision: str, initial_adapter: str | Path, device):
    """Load the locked float32 base, expand from V3, and return the evidence."""
    from peft import get_peft_model
    from transformers import AutoModelForCausalLM, AutoTokenizer

    tokenizer = AutoTokenizer.from_pretrained(
        config.model_id,
        revision=revision,
        local_files_only=True,
    )
    if tokenizer.pad_token_id is None:
        if tokenizer.eos_token is None:
            raise AdapterExpansionError("tokenizer must define pad_token or eos_token")
        tokenizer.pad_token = tokenizer.eos_token
    model = AutoModelForCausalLM.from_pretrained(
        config.model_id,
        revision=revision,
        torch_dtype=torch.float32,
        local_files_only=True,
    )
    model.config.use_cache = False
    model = get_peft_model(model, make_expanded_lora_config(config))
    expansion = load_parent_attention_adapter(model, initial_adapter)
    model.to(device)
    return model, tokenizer, revision, device, expansion


def load_expanded_candidate_model_and_tokenizer(
    config,
    *,
    revision: str,
    adapter_dir: str | Path,
    device,
):
    """Load the V2 candidate for evaluation and enforce its seven-target config."""
    from transformers import AutoModelForCausalLM, AutoTokenizer

    tokenizer = AutoTokenizer.from_pretrained(
        config.model_id,
        revision=revision,
        local_files_only=True,
    )
    if tokenizer.pad_token_id is None:
        if tokenizer.eos_token is None:
            raise AdapterExpansionError("tokenizer must define pad_token or eos_token")
        tokenizer.pad_token = tokenizer.eos_token
    base_model = AutoModelForCausalLM.from_pretrained(
        config.model_id,
        revision=revision,
        torch_dtype=torch.float32,
        local_files_only=True,
    )
    base_model.config.use_cache = False
    model = load_expanded_candidate_model(
        base_model,
        adapter_dir,
        is_trainable=False,
        expected_config=config,
    )
    model.to(device)
    return model, tokenizer
